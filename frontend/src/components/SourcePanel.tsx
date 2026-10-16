import { useEffect } from 'react'
import type { Citation } from '../api'
import { tidyPassage } from '../format'

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
          <h2>{citation.title}</h2>
          <p className="source-meta">
            {citation.filename} · part {citation.chunkIndex + 1} · {Math.round(citation.score * 100)}% match
          </p>
        </div>
        <button type="button" className="icon-button" onClick={onClose} aria-label="Close source">
          ×
        </button>
      </header>
      <div className="source-body">
        <div className="match" aria-hidden="true">
          <span className="match-bar">
            <span style={{ width: `${Math.round(citation.score * 100)}%` }} />
          </span>
          <span>Relevance to your question</span>
        </div>
        <blockquote className="source-text">
          {tidyPassage(citation.text).map((paragraph, i) => (
            <p key={i}>{paragraph}</p>
          ))}
        </blockquote>
      </div>
    </aside>
  )
}
