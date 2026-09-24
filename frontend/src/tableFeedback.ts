import type { TableView } from './api'

export type Cue = 'deal' | 'chips' | 'fold' | 'check' | 'all-in' | 'win'

/** Only adjacent committed live frames produce feedback; restores and catch-up stay quiet. */
export function feedbackCue(before: TableView | null, after: TableView): Cue | null {
  if (!before || before.tableId !== after.tableId || after.sequence !== before.sequence + 1) return null
  if (after.status !== before.status && after.status !== 'IN_HAND') return 'win'
  if (after.handNumber !== before.handNumber || after.board.length > before.board.length) return 'deal'
  const action = after.actionLog.at(-1)
  if (!action || action.sequence === before.actionLog.at(-1)?.sequence) return null
  if (action.action.includes('全下')) return 'all-in'
  if (action.action.includes('弃牌')) return 'fold'
  if (action.action.includes('过牌')) return 'check'
  if (/加注|跟注|下注/.test(action.action)) return 'chips'
  return null
}

/** Small synthesized cues: no downloaded assets, network traffic or background playback. */
export class TableAudio {
  private context: AudioContext | null = null
  private gain: GainNode | null = null
  async enable(volume: number) {
    if (!this.context || this.context.state === 'closed') {
      this.context = new AudioContext()
      this.gain = this.context.createGain()
      this.gain.connect(this.context.destination)
    }
    await this.context.resume()
    this.setVolume(volume)
  }
  setVolume(volume: number) {
    if (this.context && this.gain) this.gain.gain.setValueAtTime(volume / 100 * .12, this.context.currentTime)
  }
  mute() { if (this.context && this.gain) this.gain.gain.setValueAtTime(0, this.context.currentTime) }
  play(cue: Cue) {
    const ctx = this.context
    if (!ctx || !this.gain || ctx.state !== 'running') return
    const notes: Record<Cue, number[]> = { deal:[650,900],chips:[1300,1700],fold:[240],check:[440], 'all-in':[330,440,660],win:[523,659,784] }
    notes[cue].forEach((frequency, i) => {
      const oscillator = ctx.createOscillator(), envelope = ctx.createGain()
      const start = ctx.currentTime + i * .075
      oscillator.type = cue === 'chips' ? 'triangle' : 'sine'
      oscillator.frequency.setValueAtTime(frequency, start)
      envelope.gain.setValueAtTime(0, start)
      envelope.gain.linearRampToValueAtTime(1, start + .008)
      envelope.gain.exponentialRampToValueAtTime(.001, start + .12)
      oscillator.connect(envelope); envelope.connect(this.gain!)
      oscillator.onended = () => { oscillator.disconnect(); envelope.disconnect() }
      oscillator.start(start); oscillator.stop(start + .14)
    })
  }
  close() { void this.context?.close().catch(() => {}); this.context = null; this.gain = null }
}
