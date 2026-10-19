const path = require('path');
const root = path.resolve(__dirname, '../../../../..');
const modules = path.join(root, 'build/js/node_modules');
module.exports = config => config.set({
  basePath: process.env.CHESSTREE_BOT_SMOKE_DIR,
  files: [
    {pattern: path.join(__dirname, 'smoke.js'), included: true},
    {pattern: 'botWorker.js', included: false},
    {pattern: '*.json', included: false},
  ],
  frameworks: ['mocha'],
  plugins: [require(path.join(modules, 'karma-mocha')), require(path.join(modules, 'karma-chrome-launcher'))],
  browsers: ['ChromeHeadless'], singleRun: true, reporters: ['progress'], port: 9881,
  browserNoActivityTimeout: 45000,
});
