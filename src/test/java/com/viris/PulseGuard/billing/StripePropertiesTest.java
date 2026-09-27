package com.viris.PulseGuard.billing;

import com.stripe.StripeClient;
import com.viris.PulseGuard.enumeration.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StripePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BillingConfig.class)
            .withPropertyValues(
                    "pulseguard.stripe.secret-key=sk_test_abc",
                    "pulseguard.stripe.webhook-secret=whsec_abc",
                    "pulseguard.stripe.prices.PRO=price_pro",
                    "pulseguard.stripe.prices.BUSINESS=price_business",
                    "pulseguard.stripe.app-base-url=http://localhost:5173/",
                    "pulseguard.stripe.connect-timeout=5s",
                    "pulseguard.stripe.read-timeout=20s",
                    "pulseguard.stripe.max-network-retries=2");

    @Test
    void bindsPricesPerPlanAndBuildsTheClient() {
        runner.run(context -> {
            StripeProperties properties = context.getBean(StripeProperties.class);
            assertThat(properties.priceFor(Plan.PRO)).isEqualTo("price_pro");
            assertThat(properties.priceFor(Plan.BUSINESS)).isEqualTo("price_business");
            assertThat(context).hasSingleBean(StripeClient.class);
        });
    }

    @Test
    void mapsAPriceBackToItsPlanAndIgnoresUnknownPrices() {
        runner.run(context -> {
            StripeProperties properties = context.getBean(StripeProperties.class);
            assertThat(properties.planForPrice("price_business")).contains(Plan.BUSINESS);
            assertThat(properties.planForPrice("price_someone_elses")).isEmpty();
        });
    }

    @Test
    void buildsFrontendUrlsWithoutADoubleSlash() {
        runner.run(context -> assertThat(context.getBean(StripeProperties.class).appUrl("/billing?checkout=cancelled"))
                .isEqualTo("http://localhost:5173/billing?checkout=cancelled"));
    }

    @Test
    void neverPrintsTheSecrets() {
        runner.run(context -> assertThat(context.getBean(StripeProperties.class).toString())
                .doesNotContain("sk_test_abc")
                .doesNotContain("whsec_abc"));
    }

    @Test
    void refusesToStartWithoutASecretKey() {
        runner.withPropertyValues("pulseguard.stripe.secret-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithoutAWebhookSecret() {
        runner.withPropertyValues("pulseguard.stripe.webhook-secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWhenAPaidPlanHasNoPrice() {
        new ApplicationContextRunner()
                .withUserConfiguration(BillingConfig.class)
                .withPropertyValues(
                        "pulseguard.stripe.secret-key=sk_test_abc",
                        "pulseguard.stripe.webhook-secret=whsec_abc",
                        "pulseguard.stripe.prices.PRO=price_pro",
                        "pulseguard.stripe.app-base-url=http://localhost:5173",
                        "pulseguard.stripe.connect-timeout=5s",
                        "pulseguard.stripe.read-timeout=20s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithAPriceForTheFreePlan() {
        runner.withPropertyValues("pulseguard.stripe.prices.FREE=price_free")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWhenTwoPlansShareAPrice() {
        runner.withPropertyValues("pulseguard.stripe.prices.BUSINESS=price_pro")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithANonHttpAppUrl() {
        runner.withPropertyValues("pulseguard.stripe.app-base-url=javascript:alert(1)")
                .run(context -> assertThat(context).hasFailed());
    }
}
