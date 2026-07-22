/**
 * 가격 표시 — 할인(정가 > 판매가)이면 %OFF + 판매가 + 취소선 정가, 아니면 판매가만.
 *
 * 결제 기준은 언제나 판매가(price). originalPrice는 표시 전용(백엔드도 price로만 청구).
 * 비할인이면 프래그먼트로 판매가만 반환해 기존 마크업(부모 <p>의 색/굵기)을 그대로 물려받는다.
 */
export default function PriceTag({
  price,
  originalPrice,
  size = "sm",
}: {
  price: number;
  originalPrice?: number | null;
  size?: "sm" | "lg";
}) {
  // 비할인(정가 없음/정가<=판매가) → 판매가만. 이 early return이 originalPrice를 number로 좁혀준다.
  if (originalPrice == null || originalPrice <= price) {
    return <>{price.toLocaleString()}원</>;
  }
  // 반올림 후 0%가 되는 미세 할인(예: 10000 vs 10001)은 "0% OFF"가 어색하니 판매가만 표시.
  const percent = Math.round((1 - price / originalPrice) * 100);
  if (percent <= 0) {
    return <>{price.toLocaleString()}원</>;
  }
  return (
    <span className="inline-flex items-baseline gap-1.5">
      <span className="text-rose-600">{percent}%</span>
      <span>{price.toLocaleString()}원</span>
      <span className={`font-normal text-gray-400 line-through ${size === "lg" ? "text-sm" : "text-xs"}`}>
        {originalPrice.toLocaleString()}원
      </span>
    </span>
  );
}
