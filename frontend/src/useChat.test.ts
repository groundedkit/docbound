import { describe, expect, it } from 'vitest'
import { completedExchanges, type Turn } from './useChat'

describe('completedExchanges', () => {
  it('pairs questions with finished answers and skips stopped or failed ones', () => {
    const turns: Turn[] = [
      { id: 1, role: 'user', text: 'first' },
      { id: 2, role: 'assistant', text: 'answer one', sources: [], cited: [], status: 'done' },
      { id: 3, role: 'user', text: 'second' },
      { id: 4, role: 'assistant', text: 'partial', sources: [], cited: null, status: 'stopped' },
      { id: 5, role: 'user', text: 'third' },
      { id: 6, role: 'assistant', text: 'oops', sources: [], cited: null, status: 'error', error: 'failed' },
      { id: 7, role: 'user', text: 'fourth' },
      { id: 8, role: 'assistant', text: 'answer four', sources: [], cited: [], status: 'done' },
    ]

    expect(completedExchanges(turns)).toEqual([
      { question: 'first', answer: 'answer one' },
      { question: 'fourth', answer: 'answer four' },
    ])
  })
})
