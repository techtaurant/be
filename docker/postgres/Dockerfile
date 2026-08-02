# 게시물 검색용 pg_bigm 확장을 포함한 PostgreSQL 이미지.
# pg_bigm은 공식 이미지에 없고 apk 패키지도 없어 소스에서 빌드해야 한다.
ARG POSTGRES_VERSION=18-alpine
FROM postgres:${POSTGRES_VERSION}

ARG PG_BIGM_VERSION=v1.2-20250903

# icu-dev가 없으면 unicode/ucol.h를 찾지 못해 빌드가 실패한다.
# Alpine 저장소에 clang/llvm 패키지가 없어 with_llvm=no로 JIT 비트코드 생성을 끈다. 확장 동작에는 영향이 없다.
RUN apk add --no-cache --virtual .pg-bigm-build-deps build-base icu-dev libxml2-dev git \
 && git clone --depth 1 --branch "${PG_BIGM_VERSION}" https://github.com/pgbigm/pg_bigm.git /tmp/pg_bigm \
 && make -C /tmp/pg_bigm USE_PGXS=1 with_llvm=no \
 && make -C /tmp/pg_bigm USE_PGXS=1 with_llvm=no install \
 && rm -rf /tmp/pg_bigm \
 && apk del .pg-bigm-build-deps
