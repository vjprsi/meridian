package io.meridian.businessday.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directed acyclic graph of end-of-period jobs keyed by job code. Edges point from a dependency to the job
 * that waits for it. Provides validation (self / missing dependencies, cycles), a deterministic topological
 * plan grouped into levels (jobs on one level are independent and may run in parallel), and readiness checks
 * used by the orchestrator to dispatch jobs as soon as their dependencies are satisfied.
 */
public final class JobGraph {

    public record Job(String code, List<String> dependsOn, int sequence, boolean critical) {
        public Job {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(new LinkedHashSet<>(dependsOn));
        }
    }

    /** A validation finding; {@code type} is a stable code (missing_dependency, self_dependency, cycle, duplicate_code). */
    public record Issue(String type, String jobCode, String message) {}

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final List<Issue> structuralIssues = new ArrayList<>();

    private JobGraph() {}

    public static JobGraph of(Collection<Job> jobs) {
        JobGraph g = new JobGraph();
        for (Job j : jobs) {
            if (g.jobs.putIfAbsent(j.code(), j) != null) {
                g.structuralIssues.add(new Issue("duplicate_code", j.code(), "Job code " + j.code() + " is defined twice"));
            }
        }
        return g;
    }

    public Map<String, Job> jobs() {
        return jobs;
    }

    public boolean contains(String code) {
        return jobs.containsKey(code);
    }

    /** All findings; an empty list means the graph is a valid DAG with resolvable dependencies. */
    public List<Issue> validate() {
        List<Issue> issues = new ArrayList<>(structuralIssues);
        for (Job j : jobs.values()) {
            for (String d : j.dependsOn()) {
                if (d.equals(j.code())) {
                    issues.add(new Issue("self_dependency", j.code(), "Job " + j.code() + " depends on itself"));
                } else if (!jobs.containsKey(d)) {
                    issues.add(new Issue("missing_dependency", j.code(),
                            "Job " + j.code() + " depends on unknown job " + d));
                }
            }
        }
        for (List<String> cycle : cycles()) {
            issues.add(new Issue("cycle", cycle.getFirst(), "Dependency cycle: " + String.join(" -> ", cycle)));
        }
        return issues;
    }

    public void requireValid() {
        List<Issue> issues = validate();
        if (!issues.isEmpty()) {
            Issue first = issues.getFirst();
            throw new DomainException(422, first.type().equals("cycle") ? "dependency_cycle" : first.type(), first.message());
        }
    }

    /**
     * Elementary cycles found by depth-first search (each reported once, as a closed path such as A -> B -> A).
     * Self-dependencies are reported separately by {@link #validate()}.
     */
    public List<List<String>> cycles() {
        List<List<String>> found = new ArrayList<>();
        Set<Set<String>> seen = new HashSet<>();
        Map<String, Integer> color = new HashMap<>(); // 0 white, 1 grey (on stack), 2 black
        Deque<String> path = new ArrayDeque<>();
        for (String start : sortedCodes()) {
            if (color.getOrDefault(start, 0) == 0) {
                dfs(start, color, path, found, seen);
            }
        }
        return found;
    }

    private void dfs(String node, Map<String, Integer> color, Deque<String> path, List<List<String>> found,
                     Set<Set<String>> seen) {
        color.put(node, 1);
        path.addLast(node);
        for (String dep : jobs.get(node).dependsOn()) {
            if (!jobs.containsKey(dep) || dep.equals(node)) {
                continue;
            }
            int c = color.getOrDefault(dep, 0);
            if (c == 0) {
                dfs(dep, color, path, found, seen);
            } else if (c == 1) {
                List<String> cycle = new ArrayList<>();
                boolean in = false;
                for (String p : path) {
                    if (p.equals(dep)) {
                        in = true;
                    }
                    if (in) {
                        cycle.add(p);
                    }
                }
                if (seen.add(new HashSet<>(cycle))) {
                    // path runs job -> dependency; report in execution order (dependency first)
                    List<String> ordered = new ArrayList<>(cycle.reversed());
                    ordered.add(ordered.getFirst());
                    found.add(ordered);
                }
            }
        }
        path.removeLast();
        color.put(node, 2);
    }

    /**
     * Execution plan: level 0 holds jobs without (in-graph) dependencies, level n jobs whose longest dependency
     * chain has length n. Within a level jobs are ordered by sequence, then code. Fails on cycles.
     */
    public List<List<String>> levels() {
        requireValid();
        Map<String, Integer> level = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Job j : jobs.values()) {
            indegree.put(j.code(), j.dependsOn().size());
            for (String d : j.dependsOn()) {
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(j.code());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        sortedCodes().stream().filter(c -> indegree.get(c) == 0).forEach(c -> {
            queue.add(c);
            level.put(c, 0);
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String c = queue.poll();
            visited++;
            for (String dep : dependents.getOrDefault(c, List.of())) {
                level.merge(dep, level.get(c) + 1, Math::max);
                if (indegree.merge(dep, -1, Integer::sum) == 0) {
                    queue.add(dep);
                }
            }
        }
        if (visited != jobs.size()) {
            throw new DomainException(422, "dependency_cycle", "Job graph contains a cycle");
        }
        int max = level.values().stream().max(Integer::compare).orElse(-1);
        List<List<String>> levels = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            int lv = i;
            levels.add(jobs.values().stream().filter(j -> level.get(j.code()) == lv)
                    .sorted(order()).map(Job::code).toList());
        }
        return levels;
    }

    public List<String> topologicalOrder() {
        return levels().stream().flatMap(List::stream).toList();
    }

    /** Jobs not yet started whose dependencies are all satisfied, in dispatch order. */
    public List<String> ready(Set<String> satisfied, Set<String> started) {
        return jobs.values().stream()
                .filter(j -> !started.contains(j.code()) && !satisfied.contains(j.code()))
                .filter(j -> satisfied.containsAll(j.dependsOn()))
                .sorted(order())
                .map(Job::code)
                .toList();
    }

    /** All jobs that (transitively) depend on {@code code}. */
    public Set<String> downstreamOf(String code) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(code));
        while (!todo.isEmpty()) {
            String c = todo.poll();
            for (Job j : jobs.values()) {
                if (j.dependsOn().contains(c) && out.add(j.code())) {
                    todo.add(j.code());
                }
            }
        }
        return out;
    }

    /**
     * The sub-graph of the given jobs (e.g. those active in one cycle). A dependency on a job outside the set (not
     * in the cycle, or inactive) is replaced by that job's own dependencies, transitively, so the ordering it
     * implied still holds (X -> inactive Y -> Z keeps X after Z). Unknown dependencies must be caught by
     * validating the full graph.
     */
    public JobGraph restrictTo(Set<String> codes) {
        List<Job> kept = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (codes.contains(j.code())) {
                Set<String> deps = new java.util.LinkedHashSet<>();
                collectInside(j.dependsOn(), codes, deps, new java.util.HashSet<>());
                kept.add(new Job(j.code(), List.copyOf(deps), j.sequence(), j.critical()));
            }
        }
        return of(kept);
    }

    private void collectInside(List<String> deps, Set<String> codes, Set<String> out, Set<String> visited) {
        for (String d : deps) {
            if (!visited.add(d)) {
                continue;
            }
            if (codes.contains(d)) {
                out.add(d);
            } else if (jobs.containsKey(d)) {
                collectInside(jobs.get(d).dependsOn(), codes, out, visited);
            }
        }
    }

    private List<String> sortedCodes() {
        return jobs.values().stream().sorted(order()).map(Job::code).toList();
    }

    private static Comparator<Job> order() {
        return Comparator.comparingInt(Job::sequence).thenComparing(Job::code);
    }
}
