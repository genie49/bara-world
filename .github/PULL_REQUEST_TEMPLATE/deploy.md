## 배포 요약

<!-- develop → main 배포 PR -->

### 포함된 변경 사항

<!-- develop에 머지된 PR 목록 -->

-

### 배포 체크리스트

- [ ] develop에서 모든 CI 통과 확인
- [ ] 환경변수 변경 시 `scripts/secrets.sh push` 완료
- [ ] DB 마이그레이션 필요 여부 확인
- [ ] 롤백 계획 확인

### 배포 후 확인

- [ ] `kubectl get pods -A` 정상 확인
- [ ] 서비스 헬스체크 정상
