import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NOT_FOUND, type Citation } from '../api'
import type { AssistantTurn } from '../useChat'
import { AssistantMessage } from './AssistantMessage'

afterEach(cleanup)

const sources: Citation[] = [
  { index: 1, documentId: 'd1', filename: 'handbook.md', chunkIndex: 4, score: 0.71, text: '27 days of paid vacation' },
  { index: 2, documentId: 'd2', filename: 'api.md', chunkIndex: 0, score: 0.6, text: 'Rate limits' },
]

function turn(overrides: Partial<AssistantTurn>): AssistantTurn {
  return { id: 1, role: 'assistant', text: '', sources, cited: null, status: 'streaming', ...overrides }
}

describe('AssistantMessage', () => {
  it('renders citations as buttons that open the source', () => {
    const onOpenSource = vi.fn()
    render(
      <AssistantMessage
        turn={turn({ text: 'You get 27 days [1].', status: 'done', cited: [1] })}
        selected={null}
        onOpenSource={onOpenSource}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Source 1: handbook.md' }))

    expect(onOpenSource).toHaveBeenCalledWith(sources[0])
  })

  it('lists only the sources the finished answer cites', () => {
    render(
      <AssistantMessage
        turn={turn({ text: 'Rate limits apply [2].', status: 'done', cited: [2] })}
        selected={null}
        onOpenSource={() => {}}
      />,
    )

    const chips = screen.getByRole('list', { name: 'Sources' })
    expect(chips.textContent).toContain('api.md')
    expect(chips.textContent).not.toContain('handbook.md')
  })

  it('shows a "not found" reply without citation links', () => {
    render(
      <AssistantMessage
        turn={turn({ text: `${NOT_FOUND} [1]`, status: 'done', cited: [] })}
        selected={null}
        onOpenSource={() => {}}
      />,
    )

    expect(screen.getByText(NOT_FOUND)).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows progress before the first token arrives', () => {
    render(<AssistantMessage turn={turn({ sources: [] })} selected={null} onOpenSource={() => {}} />)

    expect(screen.getByText('Searching your documents')).toBeTruthy()
  })

  it('shows generation errors', () => {
    render(
      <AssistantMessage
        turn={turn({ text: 'Partial', status: 'error', error: 'The model failed' })}
        selected={null}
        onOpenSource={() => {}}
      />,
    )

    expect(screen.getByRole('alert').textContent).toBe('The model failed')
  })
})
