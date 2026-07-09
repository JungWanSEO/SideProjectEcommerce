import type { MetadataRoute } from "next";

// robots.txt — Next.js App Router가 /robots.txt 로 생성한다.
//   공개 콘텐츠(상품)는 허용하고, 로그인/개인/운영 경로는 색인에서 제외. sitemap 위치를 알려 발견을 돕는다.
const SITE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      // 개인/운영/구매 흐름은 색인 불필요(검색 노출 대상 아님).
      disallow: ["/admin", "/seller", "/account", "/cart", "/checkout", "/orders"],
    },
    sitemap: `${SITE}/sitemap.xml`,
  };
}
