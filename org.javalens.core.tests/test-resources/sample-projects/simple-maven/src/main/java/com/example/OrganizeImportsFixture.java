package com.example;

import static java.lang.Math.PI;
import static java.lang.Math.max;

import java.util.concurrent.*;

import java.util.List;
import java.util.ArrayList;

/**
 * Fixture for organize_imports static/on-demand branch coverage.
 *
 * <p>The source separates imports into three pools (organize_imports keeps
 * static + on-demand verbatim, even when unused; only regular imports are
 * filtered by referenced-type usage):
 * <ul>
 *   <li>{@code import static java.lang.Math.PI} — static (used by {@link #usePi()}).</li>
 *   <li>{@code import static java.lang.Math.max} — static (unused; still kept).</li>
 *   <li>{@code import java.util.concurrent.*} — on-demand (used via Executor reference).</li>
 *   <li>{@code import java.util.List} — regular (used).</li>
 *   <li>{@code import java.util.ArrayList} — regular (unused).</li>
 * </ul>
 *
 * Verifies the static-imports-at-end and on-demand-separate-block layout.
 */
public class OrganizeImportsFixture {

    public double usePi() {
        return PI * 2.0;
    }

    public List<Executor> getExecutors() {
        return null;
    }
}
