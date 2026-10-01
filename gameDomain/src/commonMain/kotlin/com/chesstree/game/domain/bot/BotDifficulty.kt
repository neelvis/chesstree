package com.chesstree.game.domain.bot

/** Initial product budgets; these labels do not imply a calibrated rating. */
enum class BotDifficulty(val softBudgetMs: Int, val hardBudgetMs: Int, val maxDepth: Int) {
    BEGINNER(100, 150, 2),
    NORMAL(350, 500, 3),
    STRONG(900, 1200, 4),
}
