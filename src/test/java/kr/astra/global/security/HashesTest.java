package kr.astra.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashesTest {

    @Test
    void sha256_은_소문자_hex_64자() {
        assertThat(Hashes.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.sha256("가")).hasSize(64);
    }
}
