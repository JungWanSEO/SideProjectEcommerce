// 소셜 로그인 버튼(구글·카카오). 백엔드 /oauth2/authorization/{provider} 로 **전체 페이지 이동**(리다이렉트
// 플로우)이라 fetch가 아니라 <a> 링크로 연다. 로그인/회원가입 페이지가 공용으로 사용.
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export default function SocialLoginButtons() {
  return (
    <>
      <div className="my-5 flex items-center gap-3 text-xs text-muted">
        <span className="h-px flex-1 bg-line" /> 또는 <span className="h-px flex-1 bg-line" />
      </div>
      <div className="flex flex-col gap-2">
        <a
          href={`${API_BASE}/oauth2/authorization/google`}
          className="flex items-center justify-center gap-2 rounded-full border border-line bg-paper px-4 py-3 font-medium text-ink transition hover:border-clay"
        >
          <span className="font-serif text-lg font-bold">G</span> Google로 계속하기
        </a>
        <a
          href={`${API_BASE}/oauth2/authorization/kakao`}
          className="flex items-center justify-center gap-2 rounded-full bg-[#FEE500] px-4 py-3 font-medium text-[#191600] transition hover:brightness-95"
        >
          <span className="text-lg font-bold">K</span> 카카오로 계속하기
        </a>
      </div>
    </>
  );
}
