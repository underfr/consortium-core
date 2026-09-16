package org.consortium.core.economy;

/** The {@code type} field of a ledger line (specification section 2.2). */
public enum LedgerType {
    /** One line per family per delivery transaction. */
    DELIVERY,
    STARTING_CAPITAL,
    /** No amount: the starting capital was refused by the same-connection check. */
    START_GRANT_DENIED,
    ADMIN_ADD,
    ADMIN_TAKE,
    ADMIN_SET,
    API_CREDIT,
    API_DEBIT,
    /** Rank metric only, no balance change. */
    RANK_CREDIT,
    PRICE_CHANGE,
    MARKET_RESET,
    /** Written at boot when the newest ledger file holds lines the saved data never saw. */
    ROLLBACK;

    /** True for the lines that move a balance. */
    public boolean movesMoney() {
        return switch (this) {
            case DELIVERY, STARTING_CAPITAL, ADMIN_ADD, ADMIN_TAKE, ADMIN_SET, API_CREDIT, API_DEBIT -> true;
            default -> false;
        };
    }

    /** Alert rule 4: every administrative line is reported to ops. */
    public boolean isAdministrative() {
        return switch (this) {
            case ADMIN_ADD, ADMIN_TAKE, ADMIN_SET, PRICE_CHANGE, MARKET_RESET, ROLLBACK -> true;
            default -> false;
        };
    }
}
