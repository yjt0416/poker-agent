export type Suit = '♠' | '♥' | '♦' | '♣'

export type PlayingCard = {
  rank: string
  suit: Suit
}

export type AgentSeat = {
  id: string
  name: string
  subtitle: string
  stack: number
  bet: number
  mood: string
  sprite: number
  seat: string
  folded?: boolean
  thinking?: boolean
  dealer?: boolean
}

export type TableLog = {
  id: number
  name: string
  action: string
  detail: string
  tone: 'gold' | 'green' | 'red' | 'blue'
}

export const initialAgents: AgentSeat[] = [
  { id: 'vesper', name: '薇斯珀', subtitle: '狡黠诈术师', stack: 2100, bet: 0, mood: '在观察你', sprite: 0, seat: 'seat-left', dealer: true },
  { id: 'hogarth', name: '霍加斯', subtitle: '激进赌徒', stack: 2450, bet: 150, mood: '胜券在握', sprite: 1, seat: 'seat-top' },
  { id: 'mirelle', name: '米蕾尔', subtitle: '冷静分析师', stack: 2220, bet: 150, mood: '若有所思', sprite: 2, seat: 'seat-right-top' },
  { id: 'bruno', name: '布鲁诺', subtitle: '强硬老兵', stack: 2760, bet: 150, mood: '不动声色', sprite: 3, seat: 'seat-right' },
  { id: 'bunji', name: '邦吉', subtitle: '谨慎猎手', stack: 1980, bet: 0, mood: '有些紧张', sprite: 4, seat: 'seat-left-bottom', folded: true },
]

export const communityCards: PlayingCard[] = [
  { rank: '10', suit: '♥' },
  { rank: 'J', suit: '♣' },
  { rank: 'Q', suit: '♦' },
]

export const holeCards: PlayingCard[] = [
  { rank: 'A', suit: '♠' },
  { rank: 'K', suit: '♥' },
]

export const initialLogs: TableLog[] = [
  { id: 1, name: '霍加斯', action: '加注至 150', detail: '“想看下一张牌？先交点学费。”', tone: 'red' },
  { id: 2, name: '米蕾尔', action: '跟注 150', detail: '下注范围仍然合理。', tone: 'blue' },
  { id: 3, name: '布鲁诺', action: '跟注 150', detail: '他没有表现出犹豫。', tone: 'gold' },
  { id: 4, name: '邦吉', action: '弃牌', detail: '“这手我就不奉陪啦。”', tone: 'green' },
]

export const formatChips = (value: number) => new Intl.NumberFormat('zh-CN').format(value)

export function nextDemoHand(hand: number) {
  const even = hand % 2 === 0
  return {
    cards: even
      ? [{ rank: 'A', suit: '♠' }, { rank: 'K', suit: '♥' }] as PlayingCard[]
      : [{ rank: '9', suit: '♣' }, { rank: '9', suit: '♦' }] as PlayingCard[],
    board: even
      ? [{ rank: '10', suit: '♥' }, { rank: 'J', suit: '♣' }, { rank: 'Q', suit: '♦' }] as PlayingCard[]
      : [{ rank: '2', suit: '♠' }, { rank: '7', suit: '♦' }, { rank: 'K', suit: '♣' }] as PlayingCard[],
  }
}
