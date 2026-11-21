package sanctuary.agent;

/** One patch rule: a class, the method-name prefixes to patch, and how to find the target argument. */
public final class Rule {

    /** true: always pass argument 0 as the target (methods taking Object). */
    final boolean argZero;
    /** true: patch even when the method returns an object/array; blocked call returns null. */
    final boolean objectReturn;
    final String[] prefixes;

    Rule(boolean argZero, String... prefixes) {
        this(argZero, false, prefixes);
    }

    Rule(boolean argZero, boolean objectReturn, String... prefixes) {
        this.argZero = argZero;
        this.objectReturn = objectReturn;
        this.prefixes = prefixes;
    }

    boolean matches(String methodName) {
        for (String p : prefixes) {
            if (methodName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
