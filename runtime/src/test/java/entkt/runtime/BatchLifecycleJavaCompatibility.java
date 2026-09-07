package entkt.runtime;

import entkt.runtime.hook.ActionHook;
import entkt.runtime.privacy.BatchPrivacyRule;
import entkt.runtime.privacy.ViewerContext;
import entkt.runtime.privacy.PrivacyDecision;
import entkt.runtime.privacy.PrivacyRule;
import entkt.runtime.privacy.PrivacyRuleContext;
import entkt.runtime.privacy.Viewer;
import entkt.runtime.rule.RuleBatch;
import entkt.runtime.rule.RuleDecisions;
import entkt.runtime.rule.TestRuleClient;
import entkt.runtime.validation.BatchValidationRule;
import entkt.runtime.validation.ValidationDecision;
import entkt.runtime.validation.ValidationRule;
import entkt.runtime.validation.ValidationRuleContext;
import java.util.List;

/**
 * Compile-time compatibility gate for the public Java view of scalar callback
 * contracts. Each implementation overrides only the scalar abstract method;
 * the batch adapter must remain a real JVM default method.
 */
final class BatchLifecycleJavaCompatibility {
    private BatchLifecycleJavaCompatibility() {}

    static final PrivacyRuleContext<TestRuleClient> VIEWER_CONTEXT =
            new PrivacyRuleContext<>(
                    new ViewerContext(Viewer.Anonymous.INSTANCE),
                    new TestRuleClient());

    static final ValidationRuleContext<TestRuleClient> VALIDATION_CONTEXT =
            new ValidationRuleContext<>(new TestRuleClient());

    static final PrivacyRule<TestRuleClient, String> PRIVACY_LAMBDA =
            (context, value) -> PrivacyDecision.Allow.INSTANCE;

    static final PrivacyRule<TestRuleClient, String> PRIVACY_CLASS = new PrivacyRule<>() {
        @Override
        public PrivacyDecision run(PrivacyRuleContext<TestRuleClient> context, String value) {
            return PrivacyDecision.Allow.INSTANCE;
        }
    };

    static final ValidationRule<TestRuleClient, String> VALIDATION_LAMBDA =
            (context, value) -> ValidationDecision.Valid.INSTANCE;

    static final ValidationRule<TestRuleClient, String> VALIDATION_CLASS = new ValidationRule<>() {
        @Override
        public ValidationDecision validate(ValidationRuleContext<TestRuleClient> context, String value) {
            return ValidationDecision.Valid.INSTANCE;
        }
    };

    static final BatchPrivacyRule<TestRuleClient, String> NULL_PRIVACY_DECISION_BATCH =
            (context, batch) -> batch.decideEach(value -> null);

    static final BatchValidationRule<TestRuleClient, String> NULL_VALIDATION_DECISION_BATCH =
            (context, batch) -> batch.decideEach(value -> null);

    static final BatchPrivacyRule<TestRuleClient, String> NULL_PRIVACY_RESULT_BATCH =
            (context, values) -> null;

    static final BatchValidationRule<TestRuleClient, String> NULL_VALIDATION_RESULT_BATCH =
            (context, values) -> null;

    static final BatchPrivacyRule<TestRuleClient, String> PRIVACY_BATCH_CLASS = new BatchPrivacyRule<>() {
        @Override
        public RuleDecisions<PrivacyDecision> runBatch(
                PrivacyRuleContext<TestRuleClient> context,
                RuleBatch<String> batch) {
            return batch.decideEach(value -> PrivacyDecision.Allow.INSTANCE);
        }
    };

    static final BatchValidationRule<TestRuleClient, String> VALIDATION_BATCH_CLASS =
            new BatchValidationRule<>() {
        @Override
        public RuleDecisions<ValidationDecision> validateBatch(
                ValidationRuleContext<TestRuleClient> context,
                RuleBatch<String> batch) {
            return batch.decideEach(value -> ValidationDecision.Valid.INSTANCE);
        }
    };

    static final ActionHook<List<Integer>> LIST_HOOK = new ActionHook<>() {
        @Override
        public void run(List<Integer> value) {}
    };

    static RuleDecisions<PrivacyDecision> runPrivacyBatch(RuleBatch<String> batch) {
        return PRIVACY_CLASS.runBatch(VIEWER_CONTEXT, batch);
    }

    static RuleDecisions<ValidationDecision> runValidationBatch(RuleBatch<String> batch) {
        return VALIDATION_CLASS.validateBatch(VALIDATION_CONTEXT, batch);
    }

    static void runHookBatch(List<List<Integer>> values) {
        LIST_HOOK.runBatch(values);
    }

    static void clearRuleBatch(RuleBatch<?> batch) {
        batch.clear();
    }

    static void clearRuleDecisions(RuleDecisions<?> decisions) {
        decisions.clear();
    }
}
