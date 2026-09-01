-- 재발급용 refresh token 저장 테이블
-- 인메모리 캐시에 두면 서버가 재시작될 때마다 전체 사용자의 재발급 근거가 사라지므로 DB에 영속화한다.
-- 한 사용자가 여러 기기에서 동시에 로그인할 수 있어 사용자당 여러 행을 허용한다.
CREATE TABLE user_refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL
);

-- 재발급은 사용자와 토큰 해시로 한 행을 찾고, 로그아웃과 만료 정리는 사용자 단위로 훑는다.
CREATE INDEX idx_user_refresh_tokens_user_id_token_hash ON user_refresh_tokens(user_id, token_hash);

COMMENT ON TABLE user_refresh_tokens IS '사용자별 refresh token 저장소';
COMMENT ON COLUMN user_refresh_tokens.token_hash IS 'refresh token 원문의 SHA-256 해시';
COMMENT ON COLUMN user_refresh_tokens.expires_at IS 'refresh token 만료 시각';
