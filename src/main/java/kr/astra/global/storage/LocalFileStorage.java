package kr.astra.global.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 첨부파일을 서버 디스크에 저장 (astra.storage.dir, 기본 ./data/uploads).
 * 저장 위치(key)는 서버가 만들고, 사용자가 보낸 파일 이름은 경로에 쓰지 않습니다.
 * 시연 서버는 Docker 볼륨에 이 폴더를 연결해 재시작해도 파일이 남게 합니다 (compose.prod.yaml).
 */
@Component
public class LocalFileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${astra.storage.dir:./data/uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public void save(String key, InputStream in) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 저장하지 못했습니다: " + key, e);
        }
    }

    /** 파일이 없으면 null */
    public Resource load(String key) {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? new FileSystemResource(path) : null;
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 지우지 못했습니다: " + key, e);
        }
    }

    /** key 가 저장 폴더 밖을 가리키지 못하게 막음 */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("잘못된 저장 위치입니다.");
        }
        return path;
    }
}
