package entkt.runtime;

import entkt.runtime.hook.Hook;
import entkt.runtime.privacy.BatchPrivacyRule;
import entkt.runtime.privacy.PrivacyDecision;
import entkt.runtime.privacy.PrivacyRule;
import entkt.runtime.rule.RuleBatch;
import entkt.runtime.rule.RuleDecisions;
import entkt.runtime.validation.BatchValidationRule;
import entkt.runtime.validation.ValidationDecision;
import entkt.runtime.validation.ValidationRule;
import java.util.List;

/**
 * Compile-time compatibility gate for the public Java view of scalar callback
 * contracts. Each implementation overrides only the scalar abstract method;
 * the batch adapter must remain a real JVM default method.
 */
final class BatchLifecycleJavaCompatibility {
    private BatchLifecycleJavaCompatibility() {}

    static final PrivacyRule<String> PRIVACY_LAMBDA =
            value -> PrivacyDecision.Allow.INSTANCE;

    static final PrivacyRule<String> PRIVACY_CLASS = new PrivacyRule<>() {
        @Override
        public PrivacyDecision run(String value) {
            return PrivacyDecision.Allow.INSTANCE;
        }
    };

    static final ValidationRule<String> VALIDATION_LAMBDA =
            value -> ValidationDecision.Valid.INSTANCE;

    static final ValidationRule<String> VALIDATION_CLASS = new ValidationRule<>() {
        @Override
        public ValidationDecision validate(String value) {
            return ValidationDecision.Valid.INSTANCE;
        }
    };

    static final BatchPrivacyRule<String> NULL_PRIVACY_DECISION_BATCH =
            batch -> batch.decide(value -> null);

    static final BatchValidationRule<String> NULL_VALIDATION_DECISION_BATCH =
            batch -> batch.decide(value -> null);

    static final BatchPrivacyRule<String> NULL_PRIVACY_RESULT_BATCH = values -> null;

    static final BatchValidationRule<String> NULL_VALIDATION_RESULT_BATCH = values -> null;

    static final BatchPrivacyRule<String> PRIVACY_BATCH_CLASS = new BatchPrivacyRule<>() {
        @Override
        public RuleDecisions<PrivacyDecision> runBatch(RuleBatch<String> batch) {
            return batch.decide(value -> PrivacyDecision.Allow.INSTANCE);
        }
    };

    static final BatchValidationRule<String> VALIDATION_BATCH_CLASS = new BatchValidationRule<>() {
        @Override
        public RuleDecisions<ValidationDecision> validateBatch(RuleBatch<String> batch) {
            return batch.decide(value -> ValidationDecision.Valid.INSTANCE);
        }
    };

    static final Hook<List<Integer>> LIST_HOOK = new Hook<>() {
        @Override
        public void run(List<Integer> value) {}
    };

    static RuleDecisions<PrivacyDecision> runPrivacyBatch(RuleBatch<String> batch) {
        return PRIVACY_CLASS.runBatch(batch);
    }

    static RuleDecisions<ValidationDecision> runValidationBatch(RuleBatch<String> batch) {
        return VALIDATION_CLASS.validateBatch(batch);
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
