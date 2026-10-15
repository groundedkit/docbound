import { useEffect } from 'react'
import type { Citation } from '../api'

interface Props {
  citation: Citation
  onClose: () => void
}

/** Shows the exact passage an answer cited, so the reader can check the claim. */
export function SourcePanel({ citation, onClose }: Props) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <aside className="source-panel" aria-label="Source">
      <header className="source-header">
        <div>
          <p className="source-label">Source {citation.index}</p>
          <h2>{citation.filename}</h2>
          <p className="source-meta">
            Part {citation.chunkIndex + 1} · {Math.round(citation.score * 100)}% match
          </p>
        </div>
        <button type="button" className="icon-button" onClick={onClose} aria-label="Close source">
          ×
        </button>
      </header>
      <div className="source-text">{citation.text}</div>
    </aside>
  )
}
