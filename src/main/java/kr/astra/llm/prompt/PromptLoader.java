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
}
