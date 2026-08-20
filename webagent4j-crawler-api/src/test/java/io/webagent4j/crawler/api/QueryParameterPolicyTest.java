package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryParameterPolicyTest {

    @Test
    void keepAllKeepsEveryParameter() {
        QueryParameterPolicy policy = QueryParameterPolicy.keepAll();

        assertThat(policy.keeps("utm_source")).isTrue();
        assertThat(policy.keeps("id")).isTrue();
    }

    @Test
    void dropAllDropsEveryParameter() {
        QueryParameterPolicy policy = QueryParameterPolicy.dropAll();

        assertThat(policy.keeps("id")).isFalse();
    }

    @Test
    void dropKnownTrackingDropsOnlyTheConservativeList() {
        QueryParameterPolicy policy = QueryParameterPolicy.dropKnownTracking();

        assertThat(policy.keeps("utm_source")).isFalse();
        assertThat(policy.keeps("utm_medium")).isFalse();
        assertThat(policy.keeps("utm_campaign")).isFalse();
        assertThat(policy.keeps("utm_term")).isFalse();
        assertThat(policy.keeps("utm_content")).isFalse();
        assertThat(policy.keeps("id")).isTrue();
        assertThat(policy.keeps("ref")).isTrue();
    }

    @Test
    void dropKnownTrackingIsCaseInsensitive() {
        QueryParameterPolicy policy = QueryParameterPolicy.dropKnownTracking();

        assertThat(policy.keeps("UTM_SOURCE")).isFalse();
    }

    @Test
    void excludeParameterDropsAnAdditionalNameUnderKeepAll() {
        QueryParameterPolicy policy = QueryParameterPolicy.keepAll().excludeParameter("session");

        assertThat(policy.keeps("session")).isFalse();
        assertThat(policy.keeps("id")).isTrue();
    }

    @Test
    void includeParameterOverridesDropAll() {
        QueryParameterPolicy policy = QueryParameterPolicy.dropAll().includeParameter("id");

        assertThat(policy.keeps("id")).isTrue();
        assertThat(policy.keeps("other")).isFalse();
    }

    @Test
    void includeParameterTakesPrecedenceOverExcludeParameter() {
        QueryParameterPolicy policy =
                QueryParameterPolicy.keepAll().excludeParameter("id").includeParameter("id");

        assertThat(policy.keeps("id")).isTrue();
    }
}
