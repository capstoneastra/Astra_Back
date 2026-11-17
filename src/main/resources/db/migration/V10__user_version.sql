-- users 낙관적 락: 관리자 승인·거절·정지와 내 정보 수정이 동시에 일어나면 나중 쪽을 409 로 막음
ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
