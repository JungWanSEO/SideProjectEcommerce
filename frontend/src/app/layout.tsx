import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";
import { WishlistProvider } from "@/lib/wishlist";
import Header from "@/components/Header";

export const metadata: Metadata = {
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
