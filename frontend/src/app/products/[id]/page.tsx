"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { apiDelete, apiGet, apiPost, apiPut } from "@/lib/api";
import { Cart, PageResponse, Product, Review } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import ProductThumb from "@/components/ui/ProductThumb";
import Badge from "@/components/ui/Badge";
import Stars, { StarInput } from "@/components/ui/Stars";
import { buttonClass } from "@/components/ui/Button";
import WishlistButton from "@/components/ui/WishlistButton";
import { productImageSrc } from "@/lib/productImage";

/**
 * 상품 상세 페이지 (/products/[id]).
 * 사이즈(옵션)를 고르고 장바구니에 담는다. 아래엔 평점·리뷰(목록 + 작성 폼).
 * 리뷰 작성은 로그인 + 구매(PAID)한 사용자만 — 비구매는 백엔드가 403, FE는 그 메시지를 보여준다.
 */
export default function ProductDetailPage() {
  const params = useParams();
  const id = params.id as string; // 동적 세그먼트 [id]
  const router = useRouter();
  const { user } = useAuth();

  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedOptionId, setSelectedOptionId] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const [cartMsg, setCartMsg] = useState<string | null>(null);

  // 리뷰 상태 + 작성 폼
  const [reviews, setReviews] = useState<Review[]>([]);
  const [rating, setRating] = useState(5);
  const [content, setContent] = useState("");
  const [reviewImageUrl, setReviewImageUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [reviewMsg, setReviewMsg] = useState<string | null>(null);

  // 인라인 수정 (본인 리뷰)
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editRating, setEditRating] = useState(5);
  const [editContent, setEditContent] = useState("");
  const [editImageUrl, setEditImageUrl] = useState("");
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editMsg, setEditMsg] = useState<string | null>(null);

  const loadProduct = useCallback(() => apiGet<Product>(`/api/products/${id}`).then(setProduct), [id]);
  const loadReviews = useCallback(
    () =>
      apiGet<PageResponse<Review>>(`/api/products/${id}/reviews`)
        .then((page) => setReviews(page.content))
        .catch(() => setReviews([])),
    [id],
  );

  useEffect(() => {
    loadProduct()
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
    loadReviews();
  }, [loadProduct, loadReviews]);

  // 로그인 사용자가 상품을 보면 조회를 기록한다(개인화 추천 신호). best-effort — 실패해도 화면엔 영향 없음.
  useEffect(() => {
    if (!user) return;
    apiPost<void>("/api/activity/views", { productId: Number(id) }).catch(() => {});
  }, [user, id]);

  const addToCart = async () => {
    if (!user) {
      router.push("/login"); // 비로그인 → 로그인으로
      return;
    }
    if (selectedOptionId === null) {
      setCartMsg("사이즈를 선택하세요.");
      return;
    }
    setAdding(true);
    setCartMsg(null);
    try {
      await apiPost<Cart>("/api/carts/items", { optionId: selectedOptionId, quantity: 1 });
      setCartMsg("장바구니에 담았습니다.");
    } catch (e) {
      setCartMsg((e as Error).message);
    } finally {
      setAdding(false);
    }
  };

  const submitReview = async (e: FormEvent) => {
    e.preventDefault();
    if (!user) {
      router.push("/login");
      return;
    }
    if (!content.trim()) {
      setReviewMsg("리뷰 내용을 입력하세요.");
      return;
    }
    setSubmitting(true);
    setReviewMsg(null);
    try {
      await apiPost<Review>(`/api/products/${id}/reviews`, {
        rating,
        content: content.trim(),
        imageUrl: reviewImageUrl.trim() || null,
      });
      setContent("");
      setReviewImageUrl("");
      setRating(5);
      setReviewMsg("리뷰가 등록되었습니다.");
      await Promise.all([loadReviews(), loadProduct()]); // 목록 + 상품 평점 갱신
    } catch (e) {
      setReviewMsg((e as Error).message); // 403(미구매)·409(중복) 등 서버 메시지
    } finally {
      setSubmitting(false);
    }
  };

  const removeReview = async (reviewId: number) => {
    try {
      await apiDelete<void>(`/api/reviews/${reviewId}`);
      await Promise.all([loadReviews(), loadProduct()]);
    } catch (e) {
      setReviewMsg((e as Error).message);
    }
  };

  const startEdit = (r: Review) => {
    setEditingId(r.id);
    setEditRating(r.rating);
    setEditContent(r.content);
    setEditImageUrl(r.imageUrl ?? "");
    setEditMsg(null);
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditMsg(null);
  };

  const saveEdit = async (reviewId: number) => {
    if (!editContent.trim()) {
      setEditMsg("리뷰 내용을 입력하세요.");
      return;
    }
    setEditSubmitting(true);
    setEditMsg(null);
    try {
      await apiPut<Review>(`/api/reviews/${reviewId}`, {
        rating: editRating,
        content: editContent.trim(),
        imageUrl: editImageUrl.trim() || null,
      });
      setEditingId(null);
      await Promise.all([loadReviews(), loadProduct()]); // 목록 + 평점 갱신
    } catch (e) {
      setEditMsg((e as Error).message);
    } finally {
      setEditSubmitting(false);
    }
  };

  if (loading) return <p className="p-12 text-center text-muted">불러오는 중…</p>;

  if (error) {
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <p className="text-danger">에러: {error}</p>
        <Link href="/products" className="mt-4 inline-block text-clay hover:underline">
          ← 목록으로
        </Link>
      </main>
    );
  }

  if (!product) return null;

  return (
    <main className="mx-auto max-w-6xl px-6 py-10">
      <Link href="/products" className="text-sm text-muted transition hover:text-clay">
        ← 목록으로
      </Link>

      <div className="mt-6 grid gap-10 lg:grid-cols-2">
        {/* 이미지 */}
        <div className="relative">
          <ProductThumb
            name={product.name}
            src={productImageSrc(product)}
            className="aspect-[4/5] w-full rounded-2xl shadow-soft"
          />
          {product.status === "SOLD_OUT" && (
            <span className="absolute left-4 top-4">
              <Badge tone="dark">품절</Badge>
            </span>
          )}
        </div>

        {/* 정보 */}
        <div className="lg:py-4">
          {product.brandName && (
            <p className="text-xs uppercase tracking-[0.25em] text-clay">{product.brandName}</p>
          )}
          <h1 className="mt-2 font-serif text-3xl text-ink">{product.name}</h1>
          {product.categoryName && <p className="mt-1 text-sm text-muted">{product.categoryName}</p>}

          {/* 평점 요약 — 리뷰 섹션으로 스크롤 */}
          {product.ratingCount > 0 ? (
            <a href="#reviews" className="mt-3 inline-flex items-center gap-2">
              <Stars value={product.ratingAverage} className="text-sm" />
              <span className="text-sm text-muted underline-offset-2 hover:underline">
                {product.ratingAverage.toFixed(1)} · 리뷰 {product.ratingCount}개
              </span>
            </a>
          ) : (
            <p className="mt-3 text-sm text-muted">아직 리뷰가 없어요</p>
          )}

          <p className="mt-5 text-2xl font-semibold text-ink">{product.price.toLocaleString()}원</p>

          {product.description && (
            <p className="mt-5 whitespace-pre-line leading-relaxed text-ink/80">{product.description}</p>
          )}

          {/* 사이즈 선택: 품절은 비활성, 선택된 사이즈는 점토색 강조 */}
          <h2 className="mb-2 mt-8 text-sm font-medium text-muted">사이즈</h2>
          <div className="flex flex-wrap gap-2">
            {product.options.map((o) => {
              const selected = o.id === selectedOptionId;
              return (
                <button
                  key={o.id}
                  type="button"
                  disabled={o.soldOut}
                  onClick={() => setSelectedOptionId(o.id)}
                  className={`rounded-full border px-4 py-2 text-sm transition ${
                    o.soldOut
                      ? "cursor-not-allowed border-line text-line"
                      : selected
                        ? "border-clay bg-clay text-cream"
                        : "border-line text-ink hover:border-clay"
                  }`}
                >
                  <span className={o.soldOut ? "line-through" : ""}>{o.size}</span>
                  <span className={`ml-2 text-xs ${selected ? "text-cream/70" : "text-muted"}`}>
                    {o.soldOut ? "품절" : `재고 ${o.stock}`}
                  </span>
                </button>
              );
            })}
          </div>

          {/* 담기 + 찜 */}
          <div className="mt-8 flex items-center gap-3">
            <button
              type="button"
              onClick={addToCart}
              disabled={adding}
              className={buttonClass("primary", "lg")}
            >
              {adding ? "담는 중…" : "장바구니 담기"}
            </button>
            <WishlistButton productId={product.id} variant="inline" />
            {cartMsg && <span className="text-sm text-muted">{cartMsg}</span>}
          </div>
          {product.wishlistCount > 0 && (
            <p className="mt-2 text-xs text-muted">{product.wishlistCount}명이 찜한 상품이에요.</p>
          )}
          {!user && <p className="mt-2 text-xs text-muted">담으려면 로그인이 필요합니다.</p>}
        </div>
      </div>

      {/* 리뷰 섹션 */}
      <section id="reviews" className="mt-16 border-t border-line pt-10">
        <div className="flex items-end justify-between">
          <h2 className="font-serif text-2xl text-ink">
            리뷰 <span className="text-lg text-muted">({product.ratingCount})</span>
          </h2>
          {product.ratingCount > 0 && (
            <div className="flex items-center gap-2">
              <Stars value={product.ratingAverage} className="text-lg" />
              <span className="text-sm text-muted">{product.ratingAverage.toFixed(1)} / 5</span>
            </div>
          )}
        </div>

        {/* 작성 폼 */}
        <div className="mt-6 rounded-2xl border border-line bg-paper p-5">
          {user ? (
            <form onSubmit={submitReview}>
              <div className="flex items-center gap-3">
                <span className="text-sm text-muted">평점</span>
                <StarInput value={rating} onChange={setRating} />
              </div>
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                maxLength={1000}
                rows={3}
                placeholder="구매하신 상품은 어떠셨나요? (핏·소재·색감 등)"
                className="mt-3 w-full rounded-xl border border-line bg-cream px-4 py-3 text-sm text-ink placeholder:text-muted focus:border-clay focus:outline-none"
              />
              <input
                value={reviewImageUrl}
                onChange={(e) => setReviewImageUrl(e.target.value)}
                maxLength={500}
                placeholder="사진 URL (선택)"
                className="mt-2 w-full rounded-xl border border-line bg-cream px-4 py-2.5 text-sm text-ink placeholder:text-muted focus:border-clay focus:outline-none"
              />
              <div className="mt-3 flex items-center gap-3">
                <button type="submit" disabled={submitting} className={buttonClass("primary", "md")}>
                  {submitting ? "등록 중…" : "리뷰 등록"}
                </button>
                {reviewMsg && <span className="text-sm text-muted">{reviewMsg}</span>}
              </div>
              <p className="mt-2 text-xs text-muted">구매(결제 완료)한 상품에만 작성할 수 있어요.</p>
            </form>
          ) : (
            <p className="text-sm text-muted">
              리뷰를 쓰려면{" "}
              <Link href="/login" className="text-clay hover:underline">
                로그인
              </Link>
              이 필요합니다.
            </p>
          )}
        </div>

        {/* 목록 */}
        <ul className="mt-8 space-y-6">
          {reviews.length === 0 ? (
            <li className="text-sm text-muted">아직 리뷰가 없어요. 첫 리뷰를 남겨보세요.</li>
          ) : (
            reviews.map((r) => (
              <li key={r.id} className="border-b border-line pb-6">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Stars value={r.rating} className="text-sm" />
                    <span className="text-sm font-medium text-ink">{r.writerName ?? "익명"}</span>
                  </div>
                  <span className="text-xs text-muted">
                    {new Date(r.createdAt).toLocaleDateString("ko-KR")}
                  </span>
                </div>
                {editingId === r.id ? (
                  /* 인라인 수정 폼 (본인) */
                  <div className="mt-3">
                    <div className="flex items-center gap-3">
                      <span className="text-sm text-muted">평점</span>
                      <StarInput value={editRating} onChange={setEditRating} className="text-xl" />
                    </div>
                    <textarea
                      value={editContent}
                      onChange={(e) => setEditContent(e.target.value)}
                      maxLength={1000}
                      rows={3}
                      className="mt-2 w-full rounded-xl border border-line bg-cream px-4 py-3 text-sm text-ink focus:border-clay focus:outline-none"
                    />
                    <input
                      value={editImageUrl}
                      onChange={(e) => setEditImageUrl(e.target.value)}
                      maxLength={500}
                      placeholder="사진 URL (선택)"
                      className="mt-2 w-full rounded-xl border border-line bg-cream px-4 py-2.5 text-sm text-ink placeholder:text-muted focus:border-clay focus:outline-none"
                    />
                    <div className="mt-3 flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => saveEdit(r.id)}
                        disabled={editSubmitting}
                        className={buttonClass("primary", "sm")}
                      >
                        {editSubmitting ? "저장 중…" : "저장"}
                      </button>
                      <button type="button" onClick={cancelEdit} className={buttonClass("secondary", "sm")}>
                        취소
                      </button>
                      {editMsg && <span className="text-sm text-muted">{editMsg}</span>}
                    </div>
                  </div>
                ) : (
                  <>
                    <p className="mt-2 whitespace-pre-line leading-relaxed text-ink/85">{r.content}</p>
                    {r.imageUrl && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={r.imageUrl}
                        alt="리뷰 사진"
                        loading="lazy"
                        className="mt-3 h-40 w-40 rounded-xl border border-line object-cover"
                      />
                    )}
                    {user && (user.id === r.memberId || user.role === "ADMIN") && (
                      <div className="mt-2 flex gap-3">
                        {user.id === r.memberId && (
                          <button
                            type="button"
                            onClick={() => startEdit(r)}
                            className="text-xs text-muted transition hover:text-clay"
                          >
                            수정
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => removeReview(r.id)}
                          className="text-xs text-muted transition hover:text-danger"
                        >
                          삭제
                        </button>
                      </div>
                    )}
                  </>
                )}
              </li>
            ))
          )}
        </ul>
      </section>
    </main>
  );
}
