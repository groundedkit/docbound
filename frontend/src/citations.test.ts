import { describe, expect, it } from 'vitest'
import { splitCitations, stripCitations } from './citations'

describe('splitCitations', () => {
  it('turns valid [n] markers into citations', () => {
    expect(splitCitations('You get 27 days [1]. Meals are 70 euros [2][3].', new Set([1, 2, 3]))).toEqual([
      { kind: 'text', text: 'You get 27 days ' },
      { kind: 'citation', index: 1 },
      { kind: 'text', text: '. Meals are 70 euros ' },
      { kind: 'citation', index: 2 },
      { kind: 'citation', index: 3 },
      { kind: 'text', text: '.' },
    ])
  })

  it('leaves markers for unknown sources as plain text', () => {
    expect(splitCitations('See [1] and [7].', new Set([1]))).toEqual([
      { kind: 'text', text: 'See ' },
      { kind: 'citation', index: 1 },
      { kind: 'text', text: ' and [7].' },
    ])
  })

  it('returns plain text unchanged when there are no markers', () => {
    expect(splitCitations('No citations.', new Set([1]))).toEqual([{ kind: 'text', text: 'No citations.' }])
  })
})

describe('stripCitations', () => {
  it('removes markers and the space before them', () => {
    expect(stripCitations("I couldn't find that in your documents. [1]")).toBe(
      "I couldn't find that in your documents.",
    )
  })
})
