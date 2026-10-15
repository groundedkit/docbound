import { useCallback, useState } from 'react'
import type { Citation } from './api'
import { ChatPanel } from './components/ChatPanel'
import { DocumentPanel } from './components/DocumentPanel'
import { SourcePanel } from './components/SourcePanel'

export default function App() {
  const [documentCount, setDocumentCount] = useState<number | null>(null)
  const [selected, setSelected] = useState<Citation | null>(null)
  const [backendError, setBackendError] = useState<string | null>(null)
  const closeSource = useCallback(() => setSelected(null), [])

  return (
    <div className={`layout${selected ? ' with-source' : ''}`}>
      <DocumentPanel onCountChange={setDocumentCount} onError={setBackendError} />
      <ChatPanel
        documentCount={documentCount}
        backendError={backendError}
        selected={selected}
        onOpenSource={setSelected}
      />
      {selected && <SourcePanel citation={selected} onClose={closeSource} />}
    </div>
  )
}
