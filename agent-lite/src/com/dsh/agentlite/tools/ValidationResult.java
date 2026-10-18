// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** M3-02 参数校验结果；无效结果不得进入策略或执行阶段。 */
public final class ValidationResult {
    public static final String CODE_UNKNOWN_TOOL = "UNKNOWN_TOOL";
    public static final String CODE_MISSING = "MISSING_REQUIRED";
    public static final String CODE_UNKNOWN_PARAMETER = "UNKNOWN_PARAMETER";
    public static final String CODE_NULL = "NULL_VALUE";
    public static final String CODE_TYPE = "TYPE_MISMATCH";
    public static final String CODE_RANGE = "OUT_OF_RANGE";
    public static final String CODE_ENUM = "INVALID_ENUM";

    public final boolean valid;
    public final List<Error> errors;

    private ValidationResult(boolean valid, List<Error> errors) {
        this.valid = valid;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.<Error>emptyList());
    }

    public static ValidationResult invalid(List<Error> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("无效校验结果必须包含错误");
        }
        return new ValidationResult(false, errors);
    }

    public static ValidationResult invalid(String code, String parameter, String message) {
        return invalid(Collections.singletonList(new Error(code, parameter, message)));
    }

    /** 可供日志和 UI 使用的稳定错误项；不包含输入值，避免意外泄露秘密。 */
    public static final class Error {
        public final String code;
        public final String parameter;
        public final String message;

        public Error(String code, String parameter, String message) {
            this.code = require(code, "错误码");
            this.parameter = parameter == null ? "" : parameter;
            this.message = require(message, "错误说明");
        }

        @Override
        public String toString() {
            return code + (parameter.isEmpty() ? "" : "(" + parameter + ")") + ": " + message;
        }

        private static String require(String value, String label) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + "不能为空");
            return value;
        }
    }
}
