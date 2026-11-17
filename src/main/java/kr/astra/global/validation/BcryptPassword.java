package kr.astra.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 비밀번호가 BCrypt 한도(UTF-8 72바이트) 안인지 검사. null 은 통과 (@NotBlank 로 따로 검사) */
@Documented
@Constraint(validatedBy = BcryptPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptPassword {

    String message() default PasswordPolicy.TOO_LONG_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordPolicy.withinBcryptLimit(value);
        }
    }
}
