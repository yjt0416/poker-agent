import type { PlayingCard } from '../game-demo'

export function PlayingCardView({ card, hidden = false, glowing = false }: { card?: PlayingCard; hidden?: boolean; glowing?: boolean }) {
  if (hidden || !card) {
    return <div className="playing-card card-back" aria-label="暗牌"><span>AT</span></div>
  }

  const red = card.suit === '♥' || card.suit === '♦'
  return (
    <div className={`playing-card ${red ? 'red-card' : ''} ${glowing ? 'glowing-card' : ''}`} aria-label={`${card.rank}${card.suit}`}>
      <span className="card-rank">{card.rank}</span>
      <span className="card-suit">{card.suit}</span>
      <span className="card-watermark">{card.suit}</span>
    </div>
  )
}
