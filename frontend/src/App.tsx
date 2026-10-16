import { useCallback, useState } from 'react'
import type { Citation, DocumentRecord } from './api'
import { ChatPanel } from './components/ChatPanel'
import { DocumentPanel } from './components/DocumentPanel'
import { SourcePanel } from './components/SourcePanel'

export default function App() {
  const [documents, setDocuments] = useState<DocumentRecord[] | null>(null)
  const [selected, setSelected] = useState<Citation | null>(null)
  const [backendError, setBackendError] = useState<string | null>(null)
  const closeSource = useCallback(() => setSelected(null), [])

  return (
    <div className={`layout${selected ? ' with-source' : ''}`}>
      <DocumentPanel onDocumentsChange={setDocuments} onError={setBackendError} />
      <ChatPanel
        documents={documents}
        backendError={backendError}
        selected={selected}
        onOpenSource={setSelected}
      />
      {selected && <SourcePanel citation={selected} onClose={closeSource} />}
    </div>
  )
}
