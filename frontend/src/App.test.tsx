import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('Agent Tavern table', () => {
  it('renders the table with private cards and legal actions', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: '无上限德州扑克' })).toBeInTheDocument()
    expect(screen.getByLabelText('A♠')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /跟注 150/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /加注 300/ })).toBeEnabled()
  })

  it('adds safe player table talk to the visible action log', () => {
    render(<App />)

    const input = screen.getByLabelText('牌桌发言')
    fireEvent.change(input, { target: { value: '你们真的觉得我在诈唬？' } })
    fireEvent.click(screen.getByRole('button', { name: /发送到牌桌/ }))

    expect(screen.getByText('“你们真的觉得我在诈唬？”')).toBeInTheDocument()
  })

  it('moves through an agent response and enables the next hand', () => {
    vi.useFakeTimers()
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: /加注 300/ }))
    expect(screen.getByText('AI 思考中…')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1200))
    expect(screen.getByRole('button', { name: /开始下一手牌/ })).toBeInTheDocument()
  })

  it('opens and closes the compact action-log drawer', () => {
    render(<App />)

    const toggle = screen.getByRole('button', { name: '动态' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('button', { name: '关闭牌桌动态' }))
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })
})
