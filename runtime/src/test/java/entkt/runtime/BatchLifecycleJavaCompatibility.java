package entkt.runtime;

import entkt.runtime.hook.Hook;
import entkt.runtime.privacy.BatchPrivacyRule;
import entkt.runtime.privacy.PrivacyDecision;
import entkt.runtime.privacy.PrivacyRule;
import entkt.runtime.validation.BatchValidationRule;
import entkt.runtime.validation.ValidationDecision;
import entkt.runtime.validation.ValidationRule;
import java.util.Collections;
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

    static final BatchPrivacyRule<String> NULL_PRIVACY_BATCH =
            values -> Collections.singletonList(null);

    static final BatchValidationRule<String> NULL_VALIDATION_BATCH =
            values -> Collections.singletonList(null);

    static final Hook<List<Integer>> LIST_HOOK = new Hook<>() {
        @Override
        public void run(List<Integer> value) {}
    };

    static List<PrivacyDecision> runPrivacyBatch(List<String> values) {
        return PRIVACY_CLASS.runBatch(values);
    }

    static List<ValidationDecision> runValidationBatch(List<String> values) {
        return VALIDATION_CLASS.validateBatch(values);
    }

    static void runHookBatch(List<List<Integer>> values) {
        LIST_HOOK.runBatch(values);
    }
}
