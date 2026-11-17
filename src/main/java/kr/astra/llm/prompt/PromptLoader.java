package kr.astra.llm.prompt;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
@Component
public class PromptLoader {
    public String load(String name) {
        if (!name.matches("[a-z-]+")) throw new IllegalArgumentException("Invalid prompt name");
        try { return new ClassPathResource("prompts/" + name + ".md").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Missing prompt: " + name, e); }
    }
    /** 프롬프트 원문 대신 메타데이터에 남기는 짧은 지문 (어느 버전 프롬프트였는지만 추적) */
    public static String fingerprint(String prompt) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
