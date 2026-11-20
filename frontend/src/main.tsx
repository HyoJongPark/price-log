import { createRoot } from 'react-dom/client'

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('index.html에 #root 요소가 필요합니다.')
}

createRoot(rootElement).render(
  <main>
    <h1>프라이스로그</h1>
  </main>,
)
