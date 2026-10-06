package io.meridian.businessday.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobGraphTest {

    private static JobGraph.Job job(String code, int seq, String... deps) {
        return new JobGraph.Job(code, List.of(deps), seq, true);
    }

    /** accrual -> (capitalisation, fees) -> gl_close; snapshot independent. */
    private static JobGraph eod() {
        return JobGraph.of(List.of(
                job("GL_CLOSE", 100, "CAPITALISE", "FEES"),
                job("FEES", 20, "ACCRUAL"),
                job("CAPITALISE", 10, "ACCRUAL"),
                job("ACCRUAL", 100),
                job("SNAPSHOT", 50)));
    }

    @Test
    void levelsGroupIndependentJobsInDependencyOrder() {
        assertThat(eod().levels()).containsExactly(
                List.of("SNAPSHOT", "ACCRUAL"),
                List.of("CAPITALISE", "FEES"),
                List.of("GL_CLOSE"));
        assertThat(eod().topologicalOrder()).containsExactly("SNAPSHOT", "ACCRUAL", "CAPITALISE", "FEES", "GL_CLOSE");
    }

    @Test
    void levelUsesLongestDependencyChain() {
        JobGraph g = JobGraph.of(List.of(job("A", 1), job("B", 1, "A"), job("C", 1, "A", "B")));
        assertThat(g.levels()).containsExactly(List.of("A"), List.of("B"), List.of("C"));
    }

    @Test
    void validGraphHasNoIssues() {
        assertThat(eod().validate()).isEmpty();
        assertThat(eod().cycles()).isEmpty();
    }

    @Test
    void detectsCycleWithPath() {
        JobGraph g = JobGraph.of(List.of(job("A", 1, "C"), job("B", 1, "A"), job("C", 1, "B"), job("D", 1)));
        List<JobGraph.Issue> issues = g.validate();
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().type()).isEqualTo("cycle");
        assertThat(issues.getFirst().message()).isEqualTo("Dependency cycle: B -> C -> A -> B");
        assertThat(g.cycles().getFirst()).doesNotContain("D");
        assertThatThrownBy(g::levels).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("dependency_cycle");
    }

    @Test
    void detectsTwoNodeCycleOnce() {
        JobGraph g = JobGraph.of(List.of(job("A", 1, "B"), job("B", 1, "A")));
        assertThat(g.cycles()).hasSize(1);
        assertThat(g.cycles().getFirst()).hasSize(3);
    }

    @Test
    void detectsMissingAndSelfDependencies() {
        JobGraph g = JobGraph.of(List.of(job("A", 1, "A"), job("B", 1, "NOPE")));
        assertThat(g.validate()).extracting(JobGraph.Issue::type)
                .containsExactlyInAnyOrder("self_dependency", "missing_dependency");
        assertThatThrownBy(g::requireValid).isInstanceOf(DomainException.class).extracting("status").isEqualTo(422);
    }

    @Test
    void detectsDuplicateCodes() {
        JobGraph g = JobGraph.of(List.of(job("A", 1), job("A", 2)));
        assertThat(g.validate()).extracting(JobGraph.Issue::type).containsExactly("duplicate_code");
    }

    @Test
    void readyReleasesJobsWhenAllDependenciesAreSatisfied() {
        JobGraph g = eod();
        assertThat(g.ready(Set.of(), Set.of())).containsExactly("SNAPSHOT", "ACCRUAL");
        assertThat(g.ready(Set.of("ACCRUAL"), Set.of("SNAPSHOT"))).containsExactly("CAPITALISE", "FEES");
        assertThat(g.ready(Set.of("ACCRUAL", "CAPITALISE"), Set.of("SNAPSHOT", "FEES"))).isEmpty();
        assertThat(g.ready(Set.of("ACCRUAL", "CAPITALISE", "FEES", "SNAPSHOT"), Set.of())).containsExactly("GL_CLOSE");
    }

    @Test
    void downstreamIsTransitive() {
        assertThat(eod().downstreamOf("ACCRUAL")).containsExactlyInAnyOrder("CAPITALISE", "FEES", "GL_CLOSE");
        assertThat(eod().downstreamOf("GL_CLOSE")).isEmpty();
    }

    @Test
    void restrictToDropsDependenciesOutsideTheCycle() {
        JobGraph sub = eod().restrictTo(Set.of("FEES", "GL_CLOSE"));
        assertThat(sub.jobs().get("FEES").dependsOn()).isEmpty();
        assertThat(sub.jobs().get("GL_CLOSE").dependsOn()).containsExactly("FEES");
        assertThat(sub.levels()).containsExactly(List.of("FEES"), List.of("GL_CLOSE"));
    }

    @Test
    void emptyGraphHasNoLevels() {
        assertThat(JobGraph.of(List.of()).levels()).isEmpty();
    }
}
