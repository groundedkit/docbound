import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { deleteDocument, listDocuments, uploadDocument, type DocumentRecord } from '../api'

/** While the backend is unreachable, check again this often so the page recovers by itself. */
const RETRY_MS = 5000

const ACCEPT = '.pdf,.md,.markdown,.txt,.docx,.html'

interface Upload {
  key: number
  name: string
  error?: string
}

interface Props {
  onCountChange: (count: number) => void
  /** The current load error (e.g. backend unreachable), or null once documents load. */
  onError: (message: string | null) => void
}

let nextUploadKey = 1

export function DocumentPanel({ onCountChange, onError }: Props) {
  const [documents, setDocuments] = useState<DocumentRecord[] | null>(null)
  const [uploads, setUploads] = useState<Upload[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const refresh = useCallback(async () => {
    try {
      const list = await listDocuments()
      setDocuments(list)
      setLoadError(null)
      onCountChange(list.length)
      onError(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Could not load documents'
      setLoadError(message)
      onError(message)
    }
  }, [onCountChange, onError])

  useEffect(() => {
    let active = true
    listDocuments().then(
      (list) => {
        if (!active) return
        setDocuments(list)
        onCountChange(list.length)
      },
      (error: unknown) => {
        if (!active) return
        const message = error instanceof Error ? error.message : 'Could not load documents'
        setLoadError(message)
        onError(message)
      },
    )
    return () => {
      active = false
    }
  }, [onCountChange, onError])

  useEffect(() => {
    if (loadError === null) return
    const timer = window.setInterval(() => void refresh(), RETRY_MS)
    return () => window.clearInterval(timer)
  }, [loadError, refresh])

  async function upload(files: FileList | File[]) {
    // One at a time: each upload is embedded synchronously, so parallel uploads only compete for the model.
    for (const file of Array.from(files)) {
      const key = nextUploadKey++
      setUploads((all) => [...all, { key, name: file.name }])
      try {
        await uploadDocument(file)
        setUploads((all) => all.filter((u) => u.key !== key))
        await refresh()
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Upload failed'
        setUploads((all) => all.map((u) => (u.key === key ? { ...u, error: message } : u)))
      }
    }
  }

  async function remove(document: DocumentRecord) {
    if (!window.confirm(`Delete "${document.filename}"? It will no longer be used to answer questions.`)) return
    try {
      await deleteDocument(document.id)
    } finally {
      await refresh()
    }
  }

  function onDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    if (event.dataTransfer.files.length) void upload(event.dataTransfer.files)
  }

  return (
    <section className="documents" aria-label="Documents">
      <h2>Documents</h2>

      <div
        className={`dropzone${dragging ? ' dragging' : ''}`}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <button type="button" className="button primary" onClick={() => input.current?.click()}>
          Upload files
        </button>
        <p>or drop them here</p>
        <p className="hint">PDF, DOCX, Markdown, TXT, HTML · up to 20 MB</p>
        <input
          ref={input}
          type="file"
          accept={ACCEPT}
          multiple
          hidden
          onChange={(event) => {
            if (event.target.files) void upload(event.target.files)
            event.target.value = ''
          }}
        />
      </div>


      <ul className="document-list">
        {uploads.map((u) => (
          <li key={u.key} className={`document${u.error ? ' failed' : ' pending'}`}>
            <div className="document-info">
              <span className="document-name">{u.name}</span>
              <span className="document-meta">{u.error ?? 'Indexing…'}</span>
            </div>
            {u.error && (
              <button
                type="button"
                className="icon-button"
                aria-label={`Dismiss ${u.name}`}
                onClick={() => setUploads((all) => all.filter((x) => x.key !== u.key))}
              >
                ×
              </button>
            )}
          </li>
        ))}
        {documents?.map((document) => (
          <li key={document.id} className="document">
            <div className="document-info">
              <span className="document-name" title={document.filename}>
                {document.filename}
              </span>
              <span className="document-meta">
                {document.chunkCount} {document.chunkCount === 1 ? 'part' : 'parts'} · {formatSize(document.sizeBytes)}
              </span>
            </div>
            <button
              type="button"
              className="icon-button"
              aria-label={`Delete ${document.filename}`}
              onClick={() => void remove(document)}
            >
              ×
            </button>
          </li>
        ))}
      </ul>
      {documents?.length === 0 && uploads.length === 0 && <p className="hint">No documents yet.</p>}
    </section>
  )
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
