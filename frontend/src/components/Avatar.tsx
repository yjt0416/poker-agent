type AvatarProps = {
  sprite: number
  name: string
  large?: boolean
}

export function Avatar({ sprite, name, large = false }: AvatarProps) {
  const column = sprite % 4
  const row = Math.floor(sprite / 4)

  return (
    <div
      className={`avatar ${large ? 'avatar-large' : ''}`}
      aria-label={`${name} 角色头像`}
      role="img"
      style={{
        backgroundPosition: `${(column / 3) * 100}% ${row * 100}%`,
      }}
    />
  )
}
