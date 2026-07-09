"use client";

import { useEffect, useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import { apiGet, apiPost, apiPut, apiDelete } from "@/lib/api";
import { Address } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import Badge from "@/components/ui/Badge";
import { buttonClass } from "@/components/ui/Button";
import Skeleton from "@/components/ui/Skeleton";

/**
 * 배송지 관리 페이지 (/account/addresses). 인증 필요(httpOnly 쿠키).
 * 백엔드가 모든 변경 후 "내 주소 목록"을 돌려주므로(장바구니 패턴), 응답으로 그대로 다시 그린다.
 * 기본배송지 1개 불변식·소유권 검증은 서버가 보장 — FE는 화면만.
 */

type Draft = {
  recipient: string;
  phone: string;
  zipcode: string;
  address1: string;
  address2: string;
};

const EMPTY: Draft = { recipient: "", phone: "", zipcode: "", address1: "", address2: "" };

export default function AddressBookPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [addresses, setAddresses] = useState<Address[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null); // null = 추가 모드, 값 = 수정 대상
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null); // 기본설정/삭제 진행 중인 항목

  // 비로그인 → 로그인으로
  useEffect(() => {
    if (!authLoading && !user) router.replace("/login");
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<Address[]>("/api/addresses")
      .then(setAddresses)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  const openAdd = () => {
    setEditingId(null);
    setDraft(EMPTY);
    setFormOpen(true);
    setError(null);
  };
  const openEdit = (a: Address) => {
    setEditingId(a.id);
    setDraft({
      recipient: a.recipient,
      phone: a.phone,
      zipcode: a.zipcode,
      address1: a.address1,
      address2: a.address2 ?? "",
    });
    setFormOpen(true);
    setError(null);
  };
  const closeForm = () => {
    setFormOpen(false);
    setEditingId(null);
    setDraft(EMPTY);
  };

  // 추가(POST)/수정(PUT) — 둘 다 갱신된 목록을 돌려받아 그대로 렌더
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const body = { ...draft, address2: draft.address2.trim() || null }; // 상세주소 비우면 null
      const updated =
        editingId == null
          ? await apiPost<Address[]>("/api/addresses", body)
          : await apiPut<Address[]>(`/api/addresses/${editingId}`, body);
      setAddresses(updated);
      closeForm();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const setDefault = async (id: number) => {
    setBusyId(id);
    setError(null);
    try {
      setAddresses(await apiPut<Address[]>(`/api/addresses/${id}/default`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (id: number) => {
    setBusyId(id);
    setError(null);
    try {
      setAddresses(await apiDelete<Address[]>(`/api/addresses/${id}`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <div className="mb-8 flex items-center justify-between">
          <Skeleton className="h-9 w-40" />
          <Skeleton className="h-11 w-32 rounded-full" />
        </div>
        <ul className="flex flex-col gap-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <li key={i} className="rounded-2xl border border-line bg-paper p-5">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 space-y-2">
                  <Skeleton className="h-5 w-32" />
                  <Skeleton className="h-4 w-28" />
                  <Skeleton className="h-4 w-64" />
                </div>
                <div className="flex shrink-0 flex-col items-end gap-2">
                  <Skeleton className="h-8 w-20 rounded-full" />
                  <Skeleton className="h-4 w-20" />
                </div>
              </div>
            </li>
          ))}
        </ul>
      </main>
    );
  if (!user) return null; // 리다이렉트 중

  const inputClass =
    "rounded-xl border border-line bg-paper px-4 py-2.5 text-ink outline-none transition placeholder:text-muted focus:border-clay";

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <div className="mb-8 flex items-center justify-between">
        <h1 className="text-3xl font-bold text-ink">배송지 관리</h1>
        {!formOpen && (
          <button onClick={openAdd} className={buttonClass("primary", "md")}>
            + 새 배송지
          </button>
        )}
      </div>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}

      {/* 추가/수정 폼 */}
      {formOpen && (
        <form onSubmit={submit} className="mb-8 rounded-2xl border border-line bg-paper p-6">
          <h2 className="mb-4 font-serif text-lg text-ink">
            {editingId == null ? "새 배송지" : "배송지 수정"}
          </h2>
          <div className="grid gap-3 sm:grid-cols-2">
            <input
              className={inputClass}
              placeholder="수령인"
              required
              maxLength={50}
              value={draft.recipient}
              onChange={(e) => setDraft({ ...draft, recipient: e.target.value })}
            />
            <input
              className={inputClass}
              placeholder="연락처 (010-0000-0000)"
              required
              maxLength={30}
              value={draft.phone}
              onChange={(e) => setDraft({ ...draft, phone: e.target.value })}
            />
            <input
              className={inputClass}
              placeholder="우편번호"
              required
              maxLength={10}
              value={draft.zipcode}
              onChange={(e) => setDraft({ ...draft, zipcode: e.target.value })}
            />
            <input
              className={`${inputClass} sm:col-span-2`}
              placeholder="기본주소 (도로명/지번)"
              required
              maxLength={200}
              value={draft.address1}
              onChange={(e) => setDraft({ ...draft, address1: e.target.value })}
            />
            <input
              className={`${inputClass} sm:col-span-2`}
              placeholder="상세주소 (선택)"
              maxLength={200}
              value={draft.address2}
              onChange={(e) => setDraft({ ...draft, address2: e.target.value })}
            />
          </div>
          <div className="mt-4 flex gap-2">
            <button type="submit" disabled={saving} className={buttonClass("primary", "md")}>
              {saving ? "저장 중…" : "저장"}
            </button>
            <button type="button" onClick={closeForm} className={buttonClass("secondary", "md")}>
              취소
            </button>
          </div>
        </form>
      )}

      {/* 목록 */}
      {addresses.length === 0 && !formOpen ? (
        <div className="rounded-2xl border border-line bg-paper p-12 text-center text-muted">
          저장된 배송지가 없습니다. 새 배송지를 추가해 보세요.
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {addresses.map((a) => (
            <li key={a.id} className="rounded-2xl border border-line bg-paper p-5">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <p className="flex items-center gap-2 font-medium text-ink">
                    {a.recipient}
                    {a.isDefault && <Badge tone="sage">기본배송지</Badge>}
                  </p>
                  <p className="mt-1 text-sm text-muted">{a.phone}</p>
                  <p className="mt-1 text-sm text-ink/80">
                    [{a.zipcode}] {a.address1}
                    {a.address2 ? ` ${a.address2}` : ""}
                  </p>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-2">
                  {!a.isDefault && (
                    <button
                      onClick={() => setDefault(a.id)}
                      disabled={busyId === a.id}
                      className={buttonClass("secondary", "sm")}
                    >
                      기본설정
                    </button>
                  )}
                  <div className="flex gap-3 text-sm">
                    <button
                      onClick={() => openEdit(a)}
                      className="text-muted transition hover:text-clay"
                    >
                      수정
                    </button>
                    <button
                      onClick={() => remove(a.id)}
                      disabled={busyId === a.id}
                      className="text-muted transition hover:text-danger disabled:opacity-50"
                    >
                      삭제
                    </button>
                  </div>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
