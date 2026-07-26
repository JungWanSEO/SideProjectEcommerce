-- #7 게스트 장바구니: cart를 회원(member_id) 또는 게스트(cart_token) 소유로. 정확히 하나만 설정된다.
--  · member_id를 NULL 허용으로(게스트 카트는 회원이 없음). 기존 UNIQUE(member_id)는 MySQL이 NULL 중복을
--    허용하므로 게스트 카트 다수가 member_id=NULL로 공존 가능(유지).
--  · cart_token: 게스트 쿠키 토큰(추측불가 UUID). UNIQUE(회원 카트는 NULL — NULL 중복 허용). 로그인 시
--    게스트 카트 항목을 회원 카트로 병합(합산)한 뒤 게스트 카트는 삭제된다.
ALTER TABLE cart MODIFY COLUMN member_id bigint NULL;
ALTER TABLE cart ADD COLUMN cart_token varchar(80) NULL;
ALTER TABLE cart ADD CONSTRAINT uk_cart_token UNIQUE (cart_token);
