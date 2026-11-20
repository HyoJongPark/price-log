# 프라이스로그 개발

- 저장소 루트에서 현재 체크아웃의 `graft/INDEX.md`를 확인한다. 인덱스가 없거나 다른 저장소를 가리키면 여기서 `graft build`를 실행한다. 처음 볼 때는 `graft map`, 작업을 시작할 때는 `graft ask "<요청과 관련된 이름>" --source`로 위치를 확인한다. 호출 관계는 `graft callers`, 전체 사용처는 `graft grep`으로 찾는다. 생략된 줄은 표시된 범위를 열고, 그래프에 없는 파일은 직접 읽는다. 구조가 크게 바뀌면 `graft build`로 갱신한다. Graft가 설치되어 있지 않으면 현재 체크아웃 안에서 `rg --files`와 `rg`로 파일과 심볼을 찾는다.
- 기능 구현, 버그 수정, 리팩터링과 그 후속 변경은 [개발 워크플로우](.agents/skills/development-workflow/SKILL.md)를 따른다. 여러 단위로 나눌 요청이나 “이어서 해줘”로 재개하는 작업도 이 스킬에서 시작한다. 문서 오탈자나 단순 문구 수정은 해당 위치와 표시 결과만 확인하면 된다. 설명만 요청받으면 코드에서 확인해 답한다.
- PoC 동작을 찾을 때는 [제품 가이드](docs/product.md)를 읽는다. 기능 추가·동작 변경·버그 수정은 구현 전부터 [테스트 워크플로우](.agents/skills/test-workflow/SKILL.md)를 적용한다. 테스트만 작성·수정·리뷰하는 요청은 [테스트 판단 기준](docs/testing.md)에서 시작한다.
- 작업은 로컬 diff 리뷰에서 마친다. 커밋 실행·메시지 추천·커밋 분할은 [커밋 스킬](.agents/skills/commit-changes/SKILL.md), 브랜치 병합·푸시는 [병합 스킬](.agents/skills/merge-branch/SKILL.md)을 따른다. 커밋·병합·푸시는 각각 사용자가 요청한 범위에서만 진행한다.
