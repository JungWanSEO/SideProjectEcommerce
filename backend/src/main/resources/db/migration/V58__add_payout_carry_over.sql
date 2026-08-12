-- #8 후속 P6: 지급 묶음의 **이월 잔액**(carry-over) — 음수 정산을 다음 기간으로 넘긴다.
--
--  · payout.carried_in   : 직전 기간에서 넘어온 잔액(≤0). 이번 지급액에서 선차감된다.
--  · payout.carried_over : 이번 기간에서 다음으로 넘기는 잔액(≤0).
--
-- 왜 필요한가 — 기존엔 기간 net이 음수면 지급 묶음 자체를 만들지 않고 400을 던졌다(전부-아니면-전무).
--   반품 역분개나 셀러 귀책 과금이 그 기간 매출을 넘으면 **정상 매출까지 통째로 미지급**되고,
--   셀러 체감은 "정산이 안 나왔다"가 된다. 게다가 음수 항목이 payout_id=null로 남아 다음에 더 넓은
--   기간으로 쓸어담기를 기대하는 구조라, "얼마나 넓혀야 하는지"를 아무도 알 수 없었다.
--
-- 새 모델: settleable = 이번 기간 net + carried_in
--            지급액(total_net) = max(0, settleable)   ← 음수 송금은 여전히 없다
--            carried_over      = min(0, settleable)   ← 부족분만 다음으로
--   기간 엔트리는 지급액이 0이어도 이 묶음에 **전부 편입**된다 — 항목이 정확히 한 번만 소비되고
--   "이 기간은 0원 정산, N원 이월"이라는 기록이 남는다(지급 이력이 끊기지 않는다).
--
-- 부호 규약: 둘 다 0 이하. 양수 이월(선지급 채권)은 v1 범위 밖이다.
ALTER TABLE payout ADD COLUMN carried_in bigint NOT NULL DEFAULT 0;
ALTER TABLE payout ADD COLUMN carried_over bigint NOT NULL DEFAULT 0;
