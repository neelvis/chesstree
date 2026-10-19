describe('Stage 1 reported-position production worker', function () {
  this.timeout(40000);
  const cases = ['queen-defense-142', 'rook-shuffle-193', 'exchange-267', 'army-transfer-474'];
  let manifest;
  before(async () => { manifest = await (await fetch('/base/manifest.json')).json(); });
  const token = move => JSON.stringify([move.actor, move.from.vertex, move.from.column, move.from.row,
    move.to.vertex, move.to.column, move.to.row, move.promotion || null]);
  async function requestFor(id) { return (await fetch(`/base/${id}.json`)).json(); }
  function validate(reply, request, fixture) {
    for (const key of ['version', 'requestId', 'gameId', 'positionRevision', 'rulesVersion', 'engineVersion',
      'searchModel', 'profile', 'profileCatalogVersion', 'difficulty', 'evaluationVersion']) {
      if (reply[key] !== request[key]) throw new Error(`Wrong ${key}: ${JSON.stringify(reply)}`);
    }
    if (['FAILED', 'CANCELLED', 'TERMINAL'].includes(reply.kind)) throw new Error(JSON.stringify(reply));
    if (!fixture.legalMoves.some(move => token(move) === token(reply.move)))
      throw new Error(`Illegal ${reply.kind} move: ${JSON.stringify(reply)}`);
    if (reply.expandedNodes > request.maxNodes || reply.leafEvaluations > request.maxEvaluations)
      throw new Error('Work cap exceeded');
  }
  function choose(worker, request, fixture) {
    return new Promise((resolve, reject) => {
      const messages = [];
      const started = performance.now();
      const watchdog = setTimeout(() => { worker.terminate(); reject(new Error('35-second watchdog')); }, 35000);
      const fail = error => { clearTimeout(watchdog); reject(error); };
      worker.onerror = event => fail(new Error(event.message));
      worker.onmessage = event => {
        try {
          const reply = JSON.parse(event.data);
          validate(reply, request, fixture);
          messages.push(reply);
          if (reply.kind === 'MOVE') {
            clearTimeout(watchdog);
            if (!messages.some(message => message.kind === 'READY')) throw new Error('Missing legal fallback');
            if (reply.source !== 'COMPLETED_DEPTH') throw new Error('Missing completed analysis');
            resolve({wallMs: Math.round(performance.now() - started), messages});
          }
        } catch (error) { fail(error); }
      };
      worker.postMessage(JSON.stringify(request));
    });
  }
  for (const id of cases) {
    it(`returns legal completed analysis for ${id} at 2000 work`, async () => {
      const request = {...await requestFor(id), maxNodes: 2000, maxEvaluations: 2000};
      const fixture = manifest.cases.find(item => item.id === id);
      const worker = new Worker('/base/botWorker.js');
      try { console.log('STAGE1-WORKER-FIXED', JSON.stringify({id, ...await choose(worker, request, fixture)})); }
      finally { worker.terminate(); }
    });
  }
  it('respects the real Strong deadline on queen-defense-142', async () => {
    const request = await requestFor('queen-defense-142');
    const fixture = manifest.cases.find(item => item.id === 'queen-defense-142');
    const worker = new Worker('/base/botWorker.js');
    try { console.log('STAGE1-WORKER-STRONG', JSON.stringify(await choose(worker, request, fixture))); }
    finally { worker.terminate(); }
  });
  it('terminates Strong after fallback and completes a replacement request', async () => {
    const request = {...await requestFor('queen-defense-142'), requestId: 'stage1-retired'};
    const fixture = manifest.cases.find(item => item.id === 'queen-defense-142');
    const old = new Worker('/base/botWorker.js');
    let oldMessages = 0;
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Missing fallback before cancellation')), 10000);
        old.onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
        old.onmessage = event => {
          oldMessages++;
          try {
            const reply = JSON.parse(event.data);
            validate(reply, request, fixture);
            if (reply.kind === 'READY') { clearTimeout(timer); old.terminate(); resolve(); }
          } catch (error) { clearTimeout(timer); reject(error); }
        };
        old.postMessage(JSON.stringify(request));
      });
      const countAtTermination = oldMessages;
      const replacement = new Worker('/base/botWorker.js');
      try {
        const next = {...request, requestId: 'stage1-replacement', gameId: 'stage1-new-game',
          maxNodes: 2000, maxEvaluations: 2000};
        const result = await choose(replacement, next, fixture);
        if (oldMessages !== countAtTermination) throw new Error('Retired worker emitted a late result');
        console.log('STAGE1-WORKER-REPLACEMENT', JSON.stringify({oldMessages, ...result}));
      } finally { replacement.terminate(); }
    } finally { old.terminate(); }
  });
});
