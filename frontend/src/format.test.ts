import { describe, expect, it } from 'vitest'
import { parseBlocks, splitBold } from './format'

describe('parseBlocks', () => {
  it('groups lines into paragraphs and lists', () => {
    expect(parseBlocks('Intro line\nsecond line\n\n- one\n- two\n\n1. first\n2) second\nAfter')).toEqual([
      { kind: 'paragraph', lines: ['Intro line', 'second line'] },
      { kind: 'bullets', items: ['one', 'two'] },
      { kind: 'numbered', items: ['first', 'second'] },
      { kind: 'paragraph', lines: ['After'] },
    ])
  })

  it('accepts * and • bullets and ignores repeated blank lines', () => {
    expect(parseBlocks('* a\n• b\n\n\n\nText')).toEqual([
      { kind: 'bullets', items: ['a', 'b'] },
      { kind: 'paragraph', lines: ['Text'] },
    ])
  })

  it('keeps text that only looks like markup as a paragraph', () => {
    expect(parseBlocks('-not a bullet\n2026 was a good year')).toEqual([
      { kind: 'paragraph', lines: ['-not a bullet', '2026 was a good year'] },
    ])
  })
})

describe('splitBold', () => {
  it('splits bold spans', () => {
    expect(splitBold('A **bold** word and **another**')).toEqual([
      { bold: false, text: 'A ' },
      { bold: true, text: 'bold' },
      { bold: false, text: ' word and ' },
      { bold: true, text: 'another' },
    ])
  })

  it('leaves an unclosed marker as text while streaming', () => {
    expect(splitBold('Still **streaming')).toEqual([{ bold: false, text: 'Still **streaming' }])
  })
})
