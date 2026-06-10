package com.example;

/// One trigger per apply_cleanup catalog id, so each clean-up can be verified
/// to transform its target construct (and ONLY its target construct) in this file.
public class CleanupCatalogDemo {

    // convert_to_lambda
    public Runnable lambdaCandidate() {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println("run");
            }
        };
    }

    // pattern_matching_instanceof
    public int patternCandidate(Object o) {
        if (o instanceof String) {
            String s = (String) o;
            return s.length();
        }
        return 0;
    }

    // convert_to_switch_expression
    public int switchCandidate(int k) {
        int v;
        switch (k) {
            case 1:
                v = 10;
                break;
            case 2:
                v = 20;
                break;
            default:
                v = 0;
                break;
        }
        return v;
    }

    // string_concat_to_text_block
    public String textBlockCandidate() {
        String html = "<html>\n" +
            "  <body>\n" +
            "</html>\n";
        return html;
    }

    // do_while_rather_than_while
    public int doWhileCandidate(int target) {
        int i = 0;
        while (true) {
            i++;
            if (i >= target) {
                break;
            }
        }
        return i;
    }

    // invert_equals
    public boolean invertEqualsCandidate(String input) {
        return input.equals("expected");
    }

    // boolean_value_rather_than_comparison
    public int booleanComparisonCandidate(boolean flag) {
        if (flag == true) {
            return 1;
        }
        return 0;
    }

    // else_if
    public int elseIfCandidate(boolean a, boolean b) {
        if (a) {
            return 1;
        } else {
            if (b) {
                return 2;
            }
        }
        return 0;
    }

    // overridden_assignment
    public int overriddenAssignmentCandidate() {
        int x = 0;
        x = compute();
        return x;
    }

    private int compute() {
        return 42;
    }
}
