/**
 * Performance-profile-only benchmark harness for comparing Redis Lua,
 * WATCH/MULTI/EXEC, and Redisson RLock while preserving the same draft
 * autosave and dirty-removal atomicity rules.
 *
 * <p>This package is not part of the production draft API. Its components
 * are activated only by the {@code perf} Spring profile.</p>
 */
package com.ktb.community.benchmark.draftatomicity;
