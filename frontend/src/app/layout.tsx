import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";
import { WishlistProvider } from "@/lib/wishlist";
import Header from "@/components/Header";

// 사이트 절대 URL — OG 이미지·canonical의 기준점. 배포 시 NEXT_PUBLIC_SITE_URL로 덮어쓴다.
const SITE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export const metadata: Metadata = {
  // metadataBase가 없으면 상대 경로 OG 이미지가 절대 URL로 못 바뀌어(빌드 경고) SNS/봇이 이미지를 못 읽는다.
  metadataBase: new URL(SITE),
  title: "ATELIER — 패션 셀렉트샵",
  description: "패션 커머스 (포트폴리오)",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  // 폰트(Pretendard·나눔명조)는 globals.css에서 로드한다. lang=ko로 한글 우선.
  return (
    <html lang="ko">
      <body className="min-h-screen bg-cream text-ink antialiased">
        <AuthProvider>
          <WishlistProvider>
            <Header />
            {children}
          </WishlistProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
