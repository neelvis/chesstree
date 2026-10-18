package com.chesstree.game.domain.bot

/** Search budgets by difficulty; these labels do not imply a calibrated rating. */
enum class BotDifficulty(val softBudgetMs: Int, val hardBudgetMs: Int, val maxDepth: Int) {
    BEGINNER(100, 150, 2),
    NORMAL(350, 500, 3),
    STRONG(30_000, 35_000, 4),
}
