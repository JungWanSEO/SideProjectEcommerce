// 백엔드(Spring Boot) 응답 계약을 TypeScript 타입으로. (Swagger 기준)
// 백엔드 DTO가 바뀌면 여기도 같이 맞춘다.

/** 공통 응답 포맷: { success, message, data } */
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

/** 페이지 응답 (PageResponse<T>) */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/** 알림 성격 (#6) */
export type NotificationCategory = "TRANSACTIONAL" | "MARKETING";

/** 인앱 알림 한 건 (#6) */
export interface Notification {
  id: number;
  type: string;
  category: NotificationCategory;
  message: string;
  link: string | null;
  read: boolean;
  createdAt: string;
}

/** 커서 기반(no-offset) 피드 응답 — GET /api/products/feed. nextCursor를 다음 요청 cursor로 넘긴다. */
export interface ProductCursorResponse {
  items: Product[];
  nextCursor: number | null;
  hasNext: boolean;
}

export type ProductStatus = "ON_SALE" | "SOLD_OUT" | "DISCONTINUED";

/** 카테고리 / 브랜드 (필터 드롭다운용) — 백엔드 CategoryResponse·BrandResponse */
export interface Category {
  id: number;
  name: string;
  parentId: number | null; // 부모 카테고리 ID(null=최상위) — 2단계 계층
}
export interface Brand {
  id: number;
  name: string;
  sellerId: number | null; // 귀속 셀러 ID(null=미귀속) — 어드민 표시용
}

/** 카테고리 등록 입력 — POST /api/categories (parentId 비우면 최상위) */
export interface CategoryCreateInput {
  name: string;
  parentId: number | null;
}

/** 카테고리 수정 입력 — PUT /api/categories/{id} (parentId 비우면 최상위로 이동) */
export interface CategoryUpdateInput {
  name: string;
  parentId: number | null;
}

/** 브랜드 등록 입력 — POST /api/brands */
export interface BrandCreateInput {
  name: string;
}

/** 브랜드 수정 입력 — PUT /api/brands/{id} (이름만; 셀러 귀속은 별도) */
export interface BrandUpdateInput {
  name: string;
}

/** 상품 옵션(사이즈) — 재고/품절은 옵션 단위 */
export interface ProductOption {
  id: number;
  size: string;
  stock: number; // 물리 재고(어드민용)
  available: number; // 가용재고 = stock − 예약(지금 살 수 있는 수량)
  soldOut: boolean; // 가용재고 0
}

/** 상품 이미지(갤러리) — 대표 imageUrl 외 추가 이미지. sortOrder 순. */
export interface ProductImage {
  id: number;
  url: string;
  sortOrder: number;
}

/** 상품 (ProductResponse) */
export interface Product {
  id: number;
  name: string;
  price: number; // 판매가(결제 기준)
  originalPrice: number | null; // 정가(취소선). null=비할인. originalPrice>price일 때만 할인.
  description: string | null;
  imageUrl: string | null; // 대표 이미지 URL — 없으면 화면에서 placeholder로 폴백
  status: ProductStatus;
  categoryId: number | null;
  categoryName: string | null;
  brandId: number | null;
  brandName: string | null;
  options: ProductOption[];
  ratingCount: number; // 리뷰 수
  ratingAverage: number; // 평점 평균(소수 1자리, 리뷰 없으면 0)
  wishlistCount: number; // 찜 수(인기도 신호)
  images: ProductImage[]; // 갤러리(대표 imageUrl 외 추가 이미지)
  createdAt: string;
}

/** 찜 항목 (WishlistResponse) — 찜한 시각 + 그 상품 정보(삭제됐으면 product=null) */
export interface Wishlist {
  id: number;
  productId: number;
  wishlistedAt: string;
  product: Product | null;
}

/** 추천 응답 (RecommendationResponse) — personalized=true면 행동 기반, false면 인기순 폴백 */
export interface RecommendationResult {
  personalized: boolean;
  products: Product[];
}

/** 함께 산 상품 응답 (CoOccurrenceResponse) — cooccurrence=true면 실제 함께 산 통계, false면 카테고리/브랜드 폴백 */
export interface CoOccurrenceResult {
  cooccurrence: boolean;
  products: Product[];
}

/** 리뷰 (ReviewResponse) */
export interface Review {
  id: number;
  memberId: number;
  writerName: string | null; // 작성자 닉네임(없으면 null)
  productId: number;
  rating: number; // 1~5
  content: string;
  imageUrl: string | null; // 사진리뷰(없으면 null)
  createdAt: string;
}

/** 리뷰 정렬 키 — 백엔드 Pageable의 sort 파라미터로 그대로 전달 */
export type ReviewSort = "createdAt,desc" | "rating,desc" | "rating,asc";

/** 리뷰 평점 요약 (ReviewSummaryResponse) — 분포는 항상 5★→1★ 5행(없는 별점은 0) */
export interface ReviewSummary {
  total: number;
  average: number; // 소수 1자리
  distribution: { rating: number; count: number }[];
}

/** 장바구니 항목 (CartItemResponse) — size·stock·soldOut은 현재(라이브) 옵션 정보 */
export interface CartItem {
  productId: number;
  optionId: number;
  productName: string;
  size: string;
  price: number;
  quantity: number;
  subtotal: number;
  stock: number;
  soldOut: boolean;
}

/** 장바구니 (CartResponse) */
export interface Cart {
  memberId: number;
  items: CartItem[];
  totalQuantity: number;
}

/** 배송지(주소록) — AddressResponse. 회원당 기본배송지(isDefault) 1개. */
export interface Address {
  id: number;
  recipient: string;
  phone: string;
  zipcode: string;
  address1: string;
  address2: string | null;
  isDefault: boolean;
  createdAt: string;
}

// 주문 상태머신: 결제 대기(PENDING) → 결제 완료(PAID) → 배송중(SHIPPING) → 배송완료(DELIVERED).
// 취소(CANCELLED)는 배송 시작 전(PENDING/PAID)까지만. 배송 진행은 forward-only(어드민이 전진).
export type OrderStatus = "PENDING" | "PAID" | "SHIPPING" | "DELIVERED" | "CANCELLED";

// 결제 상태머신 (백엔드 PaymentStatus). READY→PAID/FAILED, PAID→CANCELLED(환불)
export type PaymentStatus = "READY" | "PAID" | "FAILED" | "CANCELLED";

/** 결제 (PaymentResponse) */
export interface Payment {
  id: number;
  orderId: number;
  amount: number;
  status: PaymentStatus;
  method: string;
  provider: string; // 실제 승인한 PG (TOSS/KAKAOPAY) — 페일오버 시 요청과 다를 수 있음
  pgTransactionId: string | null; // 승인 성공 시에만 채워짐
  createdAt: string;
}

/** 주문 항목 (OrderItemResponse) — 주문 시점 스냅샷(상품명·사이즈·가격) */
export interface OrderItem {
  id: number;
  productId: number;
  optionId: number;
  brandId: number | null;
  sellerId: number | null;
  productName: string;
  size: string;
  orderPrice: number;
  quantity: number;
  subtotal: number;
  status: "ACTIVE" | "CANCELLED"; // 부분환불 시 CANCELLED
}

/** 주문 배송지 스냅샷 (OrderResponse.shipping) — 주문 시점에 주소록에서 복사. 없으면 null. */
export interface ShippingInfo {
  recipient: string;
  phone: string;
  zipcode: string;
  address1: string;
  address2: string | null;
  deliveryMemo: string | null;
}

/** 주문 상세 (OrderResponse) */
/** 주문 상태 이력 1건 (OrderResponse.statusHistory) — 상세 타임라인 */
export interface OrderStatusHistory {
  fromStatus: OrderStatus | null; // 생성 시 null
  toStatus: OrderStatus;
  changedBy: number | null; // 변경 주체 회원 ID (시스템/스케줄러면 null)
  memo: string | null;
  createdAt: string;
}

export interface Order {
  id: number;
  memberId: number;
  status: OrderStatus;
  totalPrice: number; // 할인 전 총액(gross)
  discountAmount: number; // 쿠폰 할인액 (없으면 0)
  shippingFee: number; // 배송비(#4, 없으면 0). 플랫폼 수익 — 셀러 정산엔 미포함
  payableAmount: number; // 실제 결제액 = totalPrice - discountAmount + shippingFee
  couponCode: string | null; // 적용된 쿠폰 코드 (없으면 null)
  items: OrderItem[];
  shipping: ShippingInfo | null;
  courier: string | null; // 택배사 (배송 시작 후, 없으면 null)
  trackingNumber: string | null; // 운송장 번호 (없으면 null)
  statusHistory: OrderStatusHistory[]; // 상태 타임라인 (발생 순)
  createdAt: string;
}

/** 주문 목록 요약 (OrderSummaryResponse) — 목록은 가볍게(대표상품명 + 항목수) */
export interface OrderSummary {
  id: number;
  memberId: number; // 주문 회원 ID(어드민 목록에서 식별용)
  status: OrderStatus;
  totalPrice: number;
  createdAt: string;
  representativeProductName: string;
  itemCount: number;
}

/** 주문 배송 상태 전진 입력 — PATCH /api/orders/{id}/status (ADMIN, forward-only) */
export interface OrderStatusUpdateInput {
  status: OrderStatus;
  courier?: string | null; // SHIPPING일 때 택배사(선택)
  trackingNumber?: string | null; // SHIPPING일 때 운송장(선택)
}

// ───────── 셀러(Seller) ─────────

/** 셀러(입점사) — SellerResponse */
export interface Seller {
  id: number;
  name: string;
  commissionRate: number; // 플랫폼 판매수수료율 (예: 0.10)
  status: "ACTIVE" | "SUSPENDED";
  payoutAccount: string | null;
  businessNumber: string | null;
  createdAt: string;
}

// ───────── 정산(Settlement) — ADMIN 운영 ─────────

// 정산 항목 상태 (백엔드 SettlementStatus). 정산예정 → 입금완료
export type SettlementStatus = "SCHEDULED" | "PAID_OUT";

/** 정산 항목 (SettlementResponse) — 셀러별 정산: 매출 ≠ 셀러 실수령 */
export interface Settlement {
  id: number;
  paymentId: number;
  orderId: number;
  pgTransactionId: string; // 대사 조인 키
  provider: string; // 정산 대상 결제를 처리한 PG (MPG-2)
  sellerId: number | null; // 셀러(입점사) — null이면 플랫폼 직매입(미귀속)
  grossAmount: number; // 셀러 매출
  fee: number; // PG 수수료(안분)
  feeRate: number; // 적용 PG 수수료율 스냅샷 (예: 0.025) — MPG-3
  platformFee: number; // 플랫폼 판매수수료
  platformFeeRate: number; // 적용 플랫폼 수수료율 스냅샷 (예: 0.10)
  discountAmount: number; // 이 항목에 안분된 쿠폰 할인액 (없으면 0) — 쿠폰 Step 2
  discountFundedBy: string | null; // 할인 부담 주체 ("PLATFORM"/"SELLER", 없으면 null)
  netAmount: number; // 셀러 실수령 (= gross - fee - platformFee + 플랫폼부담 할인 환원)
  status: SettlementStatus;
  settledDate: string; // 입금 예정/완료일 (LocalDate "YYYY-MM-DD")
  createdAt: string;
}

/** 정산 배치 결과의 PG별 분해 (SettlementRunResponse.ProviderBreakdown) — MPG-3 */
export interface SettlementProviderBreakdown {
  provider: string;
  feeRate: number;
  count: number;
  grossAmount: number;
  fee: number;
  platformFee: number;
  netAmount: number;
  discount: number; // 쿠폰 할인 합계 — 쿠폰 Step 2
}

/** 정산 배치 결과의 셀러별 분해 (SettlementRunResponse.SellerBreakdown) — Phase 2 */
export interface SettlementSellerBreakdown {
  sellerId: number | null;
  count: number;
  grossAmount: number;
  fee: number;
  platformFee: number;
  netAmount: number;
  discount: number; // 쿠폰 할인 합계 — 쿠폰 Step 2
}

/** 정산 배치 실행 결과 (SettlementRunResponse) */
export interface SettlementRunResult {
  createdCount: number;
  totalGrossAmount: number;
  totalFee: number;
  totalPlatformFee: number;
  totalNetAmount: number;
  totalDiscount: number; // 쿠폰 할인 합계 — 쿠폰 Step 2
  byProvider: SettlementProviderBreakdown[]; // PG별 분해 — MPG-3
  bySeller: SettlementSellerBreakdown[]; // 셀러별 분해 — Phase 2
}

/** 셀러 정산서 — 셀러별 집계 (SellerSettlementSummary). sellerName 포함(서버 enrich) */
export interface SellerSettlementSummary {
  sellerId: number | null;
  sellerName: string | null; // 미귀속이면 null
  count: number;
  grossAmount: number; // 할인 후 셀러 몫
  fee: number;
  platformFee: number;
  discountAmount: number; // 쿠폰 할인 합계 (원매출 = grossAmount + discountAmount) — 쿠폰 Step 2
  netAmount: number;
}

// ───────── 지급 묶음(Payout) ─────────

export type PayoutStatus = "PENDING" | "PAID";

/** 지급 묶음 (PayoutResponse) — 셀러에게 기간별로 한 번에 지급하는 단위 */
export interface Payout {
  id: number;
  sellerId: number;
  sellerName: string | null;
  periodFrom: string; // YYYY-MM-DD
  periodTo: string;
  totalGross: number;
  totalFee: number;
  totalPlatformFee: number;
  totalNet: number; // 실지급액
  entryCount: number;
  status: PayoutStatus;
  paidAt: string | null;
  createdAt: string;
}

// ───────── 대사(Reconciliation) — ADMIN 운영 ─────────

// 불일치 유형 (백엔드 MismatchType)
export type MismatchType = "MISSING_IN_PG" | "MISSING_IN_OURS" | "AMOUNT_MISMATCH" | "STATUS_MISMATCH";

// 불일치 처리 상태 (백엔드 MismatchStatus). 미처리 → 처리됨/무시
export type MismatchStatus = "OPEN" | "RESOLVED" | "IGNORED";

/** 대사 불일치 항목 (MismatchResponse). ourAmount/pgAmount는 한쪽에만 있으면 null */
export interface Mismatch {
  id: number;
  pgTransactionId: string;
  provider: string; // 어느 PG의 거래인가 (MPG-2)
  type: MismatchType;
  ourAmount: number | null;
  pgAmount: number | null;
  detail: string;
  status: MismatchStatus;
  resolutionNote: string | null;
  createdAt: string;
}

/** 대사 결과의 PG별 분해 (ReconciliationResult.ProviderReconciliation) — MPG-2 */
export interface ReconciliationProviderBreakdown {
  provider: string;
  matched: number;
  missingInPg: number;
  missingInOurs: number;
  amountMismatch: number;
  statusMismatch: number;
  totalMismatches: number;
  alreadyHandled: number;
}

/** 대사 실행 결과 요약 (ReconciliationResult) */
export interface ReconciliationResult {
  matched: number;
  missingInPg: number;
  missingInOurs: number;
  amountMismatch: number;
  statusMismatch: number;
  totalMismatches: number;
  alreadyHandled: number;
  byProvider: ReconciliationProviderBreakdown[]; // PG별 분해 — MPG-2
}

// ───────── 쿠폰(Coupon) — Phase 2 후속 차별화 ─────────

// 할인 종류 (백엔드 DiscountType): 정액 / 정률
export type DiscountType = "FIXED_AMOUNT" | "PERCENTAGE";
// 할인 부담 주체 (백엔드 CouponFundedBy): 플랫폼 / 셀러
export type CouponFundedBy = "PLATFORM" | "SELLER";
// 쿠폰 상태 (백엔드 CouponStatus)
export type CouponStatus = "ACTIVE" | "DISABLED";
// 배포 방식 (백엔드 CouponIssueType): 공개 코드 / 회원 발급(지갑)
export type CouponIssueType = "PUBLIC" | "ISSUED";

/** 쿠폰 (CouponResponse) — ADMIN 관리 */
export interface Coupon {
  id: number;
  code: string;
  name: string;
  discountType: DiscountType;
  discountValue: number; // 정액=원, 정률=퍼센트(1~100)
  maxDiscountAmount: number | null; // 정률 상한(원). 정액/무제한이면 null
  minOrderAmount: number; // 최소 적용 대상 금액(원)
  fundedBy: CouponFundedBy; // 할인 비용 부담 주체 (정산 분담 — Step 2)
  issueType: CouponIssueType; // PUBLIC(코드 입력·무제한) / ISSUED(회원 발급·지갑·단일 사용) — Step 3
  sellerId: number | null; // null=플랫폼 와이드(주문 전체), 값=해당 셀러 상품 한정
  validFrom: string; // ISO LocalDateTime
  validUntil: string;
  status: CouponStatus;
  createdAt: string;
  totalQuantity: number | null; // 선착순 한도(장). null=무제한
  remainingQuantity: number | null; // 남은 발급 수(무제한이면 null, 소진 시 0)
  issuedCount: number; // 발급된 수(선착순 claim·지갑 발급 누계). 공개형 무제한은 0
  usedCount: number; // 사용된 수(발급형에서 유의미)
}

/** 쿠폰 생성 요청 (CouponCreateRequest) */
export interface CouponCreateInput {
  code: string;
  name: string;
  discountType: DiscountType;
  discountValue: number;
  maxDiscountAmount?: number | null;
  minOrderAmount: number;
  fundedBy: CouponFundedBy;
  issueType: CouponIssueType;
  sellerId?: number | null;
  validFrom: string;
  validUntil: string;
}

// 회원 쿠폰(쿠폰함) 사용 상태 (백엔드 MemberCouponStatus)
export type MemberCouponStatus = "UNUSED" | "USED";

/** 회원 쿠폰함의 한 장 (MemberCouponResponse) — 발급 쿠폰 + 쿠폰 상세 enrich — Step 3 */
export interface MemberCoupon {
  id: number; // member_coupon id
  couponId: number;
  code: string;
  name: string;
  discountType: DiscountType;
  discountValue: number;
  maxDiscountAmount: number | null;
  minOrderAmount: number;
  fundedBy: CouponFundedBy;
  sellerId: number | null;
  validFrom: string;
  validUntil: string;
  status: MemberCouponStatus;
  usedAt: string | null;
  usable: boolean; // 미사용 + 쿠폰 활성 + 기간 내
}

/**
 * 받을 수 있는(claimable) 선착순 쿠폰 한 장 (ClaimableCouponResponse) — 회원 관점.
 * 발급형·활성·기간 내 쿠폰을 잔여수량/마감/이미받음과 함께 내려준다.
 */
export interface ClaimableCoupon {
  id: number; // coupon id (받기 경로의 {couponId})
  code: string;
  name: string;
  discountType: DiscountType;
  discountValue: number;
  maxDiscountAmount: number | null;
  minOrderAmount: number;
  sellerId: number | null;
  validFrom: string;
  validUntil: string;
  totalQuantity: number | null; // 선착순 한도. null=무제한
  remainingQuantity: number | null; // 남은 수(무제한이면 null, 소진 시 0)
  soldOut: boolean; // 선착순 마감(무제한은 항상 false)
  alreadyClaimed: boolean; // 이 회원이 이미 받음(회원·쿠폰당 1장)
}

/** 쿠폰 미리보기 응답 (CouponPreviewResponse) — 현재 장바구니 기준 할인·예상 결제액 */
export interface CouponPreview {
  couponCode: string;
  totalPrice: number;
  discountAmount: number;
  shippingFee: number; // 배송비(#4). 할인 후 상품금액이 무료임계 이상이면 0
  payableAmount: number;
}

/** 배송비 정책 (ShippingPolicyResponse, #4) — 장바구니·체크아웃 무료배송 진행바 표시용 */
export interface ShippingPolicy {
  flatFee: number; // 정액 배송비(원)
  freeThreshold: number; // 무료배송 임계액(원, 할인 후 상품금액 기준)
}

// ─── 어드민 대시보드 (DashboardResponse) ───────────────────────────────

/** 상단 요약 카드 (DashboardResponse.Kpi) — 금액은 원(KRW) */
export interface DashboardKpi {
  totalOrders: number; // 전체 주문 수
  netRevenue: number; // 순매출(환불 차감) — PAID 결제 amount−refundedAmount 합
  pendingSettlement: number; // 정산 대기 금액(SCHEDULED net 합)
  memberCount: number;
  activeProductCount: number; // 판매 중(ON_SALE) 상품 수
}

/** 주문 상태별 건수 (DashboardResponse.OrderStatusCount) */
export interface DashboardOrderStatusCount {
  status: OrderStatus;
  count: number;
}

/** 하루치 매출 (DashboardResponse.DailyRevenue) — 빈 날도 0으로 채워진 연속 시계열 */
export interface DashboardDailyRevenue {
  date: string; // ISO LocalDate (yyyy-MM-dd)
  revenue: number;
}

/** 대시보드 한 화면 (DashboardResponse) — KPI + 상태 분포 + 매출 추이 */
export interface Dashboard {
  kpi: DashboardKpi;
  orderStatusDistribution: DashboardOrderStatusCount[];
  revenueTrend: DashboardDailyRevenue[];
}

/** 캐시 적중 통계 (CacheStatsResponse) — GET /api/monitoring/caches (ADMIN) */
export interface CacheStats {
  cacheName: string;
  requestCount: number;
  hitCount: number;
  missCount: number;
  hitRate: number; // 0..1
  evictionCount: number;
  estimatedSize: number;
}

/** 재고 임박·품절 옵션 1건 (LowStockOption) — 재고는 상품이 아니라 옵션(사이즈=SKU) 단위 */
export interface LowStockOption {
  productId: number;
  productName: string;
  productStatus: ProductStatus;
  optionId: number;
  size: string;
  stock: number; // 0이면 품절
}

/** 재고 임박·품절 리포트 (LowStockResponse) — GET /api/dashboard/low-stock (ADMIN) */
export interface LowStockReport {
  threshold: number; // 임박 기준 재고(이하)
  soldOutCount: number; // 품절(재고 0) 옵션 수 — 전체 기준
  lowStockCount: number; // 임박(1~threshold) 옵션 수 — 전체 기준
  items: LowStockOption[]; // 재고 적은 순 상위 목록
}

/** 회원 권한 (Role) — SELLER는 셀러 운영자 지정 API로만 부여된다(셀러 연결이 필요). */
export type MemberRole = "USER" | "SELLER" | "ADMIN";

/** 회원 (MemberResponse) — GET /api/members/admin (ADMIN). */
export interface Member {
  id: number;
  email: string;
  nickname: string;
  role: MemberRole;
  sellerId: number | null; // SELLER면 운영하는 셀러 ID (그 외 null)
  createdAt: string;
}

/** 회원 권한 변경 입력 — PATCH /api/members/{id}/role (ADMIN, USER ↔ ADMIN) */
export interface MemberRoleUpdateInput {
  role: MemberRole;
}

/** 감사 로그 결과 (AuditResult). */
export type AuditResult = "SUCCESS" | "FAILURE";

/** 감사 로그 (AuditLogResponse) — GET /api/audit-logs (ADMIN). 어드민 변경 이력 1건. */
export interface AuditLog {
  id: number;
  actorMemberId: number | null;
  actorEmail: string | null; // 백엔드가 회원 조회로 enrich(없으면 null)
  action: string; // 예: "PRODUCT_UPDATE"
  targetType: string | null; // 예: "PRODUCT"
  targetId: string | null; // 예: "42"
  detail: string | null; // 예: "PUT /api/products/42"
  result: AuditResult;
  createdAt: string;
}
