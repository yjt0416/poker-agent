import type { AgentSeat } from '../game-demo'
import { formatChips } from '../game-demo'
import { Avatar } from './Avatar'

export function AgentSeatView({ agent }: { agent: AgentSeat }) {
  return (
    <article className={`agent-seat ${agent.seat} ${agent.folded ? 'seat-folded' : ''} ${agent.thinking ? 'seat-thinking' : ''}`}>
      <Avatar sprite={agent.sprite} name={agent.name} large />
      <div className="seat-plate">
        <div className="seat-name-row">
          <strong>{agent.name}</strong>
          {agent.dealer && <span className="dealer-button">D</span>}
        </div>
        <span className="seat-subtitle">{agent.subtitle}</span>
        <span className="seat-stack"><i className="chip-dot" />{formatChips(agent.stack)}</span>
      </div>
      {agent.thinking && <div className="thinking-bubble"><i /><i /><i /></div>}
      {agent.folded && <span className="folded-badge">已弃牌</span>}
      {agent.bet > 0 && <span className="seat-bet"><i className="chip-stack-mini" />{agent.bet}</span>}
    </article>
  )
}
