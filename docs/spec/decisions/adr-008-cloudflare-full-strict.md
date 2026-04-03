# ADR-008: Cloudflare SSL/TLS Full Strict 모드 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

Cloudflare와 OCI VM 간 통신의 TLS 모드를 결정해야 했다.

## 선택지

1. **Flexible**: 사용자↔Cloudflare만 TLS, Cloudflare↔서버 구간은 HTTP. 설정 간단.
2. **Full**: 전 구간 TLS, 서버 인증서 유효성 검사 안 함 (자체 서명 가능).
3. **Full Strict**: 전 구간 TLS, 서버 인증서 유효성 검사 함 (신뢰할 수 있는 CA 필요).

## 결정

**Full Strict** 모드를 채택한다.

## 이유

- Flexible은 Cloudflare↔서버 구간이 평문이라 내부 스니핑에 취약
- Full은 자체 서명 인증서를 허용하므로 MITM 공격에 취약할 수 있음
- Full Strict는 Cloudflare Origin 인증서를 사용하면 설정이 간단하면서 가장 안전
- Cloudflare Origin 인증서는 무료, 유효기간 최대 15년, 콘솔에서 즉시 발급 가능

## 결과

- Cloudflare 콘솔에서 Origin 인증서 발급
- Nginx에 Origin 인증서 설치
- 전 구간(사용자↔Cloudflare↔OCI VM) TLS 암호화 보장
