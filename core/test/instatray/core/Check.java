package instatray.core;

import java.util.Objects;

/** Minimal assertions for the library's test mains (run by test.sh; no JUnit). */
final class Check {
    private static int failures = 0;

    private Check() {
    }

    static void that(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    static void eq(String name, Object expected, Object actual) {
        that(name, Objects.equals(expected, actual), "expected <" + expected + "> got <" + actual + ">");
    }

    /** Call last in each test main. */
    static void done() {
        if (failures > 0) System.exit(1);
    }
}
