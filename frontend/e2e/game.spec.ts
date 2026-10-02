import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function waitForPlayerTurn(page: Page) {
  for (let hand = 0; hand < 10; hand++) {
    let status = ''
    await expect.poll(async () => {
      const view = await (await page.request.get('/api/tables/current')).json()
      status = view.status
      return status === 'BETWEEN_HANDS' || view.legalActions.types.length > 0
    }).toBe(true)
    if (status !== 'BETWEEN_HANDS') {
      await expect(page.getByRole('button', { name: '全下', exact: true })).toBeEnabled()
      return
    }
    await page.getByRole('button', { name: '开始下一手牌 →', exact: true }).click()
  }
  throw new Error('Ten hands ended before a player turn was available')
}

async function create(page: Page, spectator = false, captureLobby = false) {
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('heading', { name: '今夜，和谁过招？' })).toBeVisible()
  if (captureLobby) {
    await expect(page.getByRole('button', { name: '落座，开局 →' })).toBeEnabled()
    await page.screenshot({ path: test.info().outputPath('lobby.png'), fullPage: true })
  }
  if (spectator) await page.getByRole('button', { name: 'AI 决策剧场', exact: true }).click()
  await page.getByRole('button', { name: '落座，开局 →' }).click()
  await expect(page.getByRole('region', { name: '扑克牌桌' })).toBeVisible()
  if (!spectator) await waitForPlayerTurn(page)
}

test('player chat reaches a second window, survives reload, and appears in replay', async ({ page, context }) => {
  await create(page, false, true)
  await page.screenshot({ path: test.info().outputPath('table-desktop.png') })
  const before = await (await page.request.get('/api/tables/current')).json()
  const second = await context.newPage()
  await second.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(second.getByRole('region', { name: '扑克牌桌' })).toBeVisible()
  await page.getByRole('textbox', { name: '牌桌发言' }).fill('浏览器验收：今晚慢慢喝茶。')
  await page.getByRole('button', { name: '发送到牌桌 ↗' }).click()
  await expect(second.locator('.agent-speech')).toContainText('今晚慢慢喝茶')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('.agent-speech')).toContainText('今晚慢慢喝茶')
  expect((await (await page.request.get('/api/tables/current')).json()).tableId).toBe(before.tableId)
  // Check or call is legal at every non-all-in human turn; folding is disabled when checking is free.
  await page.getByRole('button', { name: /CHECK \/ CALL · C/ }).click()
  await expect.poll(async () => (await (await page.request.get('/api/tables/current')).json()).version).toBeGreaterThan(before.version)
  await page.getByRole('button', { name: '牌局回放', exact: true }).click()
  await expect(page.getByRole('slider', { name: '回放时间轴' })).toBeVisible()
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  await expect(page.getByRole('button', { name: '上一步', exact: true })).toBeEnabled()
  const replay = await (await page.request.get(`/api/tables/current/replay?tableId=${before.tableId}&after=0`)).json()
  expect(replay.frames.every((frame: { holeCards: unknown[]; seats: object[] }) => frame.holeCards.length <= 2 && frame.seats.every(seat => !('holeCards' in seat)))).toBe(true)
  expect(replay.frames.some((frame: { chat: { text: string }[] }) => frame.chat.some(chat => chat.text.includes('今晚慢慢喝茶')))).toBe(true)
})

test('spectator steps and autoplay update through SSE while the log stays inside the viewport', async ({ page }) => {
  await create(page, true)
  const before = await (await page.request.get('/api/tables/current')).json()
  expect(before.holeCards).toEqual([])
  await page.getByRole('button', { name: '推进一步', exact: true }).click()
  await expect(page.getByRole('button', { name: '推进一步', exact: true })).toBeEnabled()
  await expect.poll(async () => (await (await page.request.get('/api/tables/current')).json()).version).toBe(before.version + 1)
  await page.getByLabel('观战速度').selectOption('4')
  await page.getByRole('button', { name: '自动播放', exact: true }).click()
  await expect.poll(async () => (await (await page.request.get('/api/tables/current')).json()).version).toBeGreaterThan(before.version + 15)
  await page.getByRole('button', { name: '暂停', exact: true }).click()
  const dock = await page.locator('.action-dock').boundingBox()
  expect(dock!.y + dock!.height).toBeLessThanOrEqual(900)
  expect(await page.locator('.log-item').count()).toBeGreaterThan(10)
  await expect(page.locator('.hero-cards')).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath('spectator.png') })
})

test.describe('complete tournament', () => {
test.describe.configure({ retries: 0 })
test('4x spectator autoplay reaches champion, rankings, and the final replay hand', async ({ page }) => {
  test.setTimeout(12 * 60_000)
  await create(page, true)
  await page.getByLabel('观战速度').selectOption('4')
  await page.getByRole('button', { name: '自动播放', exact: true }).click()

  await expect(page.getByRole('heading', { name: '今夜的赢家', exact: true }))
    .toBeVisible({ timeout: 11 * 60_000 })
  await expect(page.locator('.final-ranks li')).toHaveCount(6)
  await expect(page.locator('.final-ranks li').first()).toContainText('#1')
  await expect(page.getByRole('button', { name: '回看这场较量', exact: true })).toBeVisible()
  const final = await (await page.request.get('/api/tables/current')).json()
  expect(final.status).toBe('COMPLETE')
  expect(final.actionLog.at(-1).action).toBe('本手结算')
  expect(final.rankings.map((rank: {position: number}) => rank.position).sort()).toEqual([1,2,3,4,5,6])
  expect(final.rankings.reduce((sum: number, rank: {stack: number}) => sum + rank.stack, 0)).toBe(60_000)
  await page.screenshot({ path: test.info().outputPath('tournament-results.png'), fullPage: true })

  await page.getByRole('button', { name: '回看这场较量', exact: true }).click()
  await expect(page.getByText('公共观战视角，隐藏所有底牌')).toBeVisible()
  const loadAll = page.getByRole('button', { name: '加载全部后续记录', exact: true })
  await expect(loadAll).toBeVisible()
  await loadAll.click()
  await expect(loadAll).toBeHidden({ timeout: 60_000 })
  const hands = page.getByLabel('跳转手牌').locator('option')
  expect(await hands.count()).toBeGreaterThan(1)
  const lastHand = await hands.last().getAttribute('value')
  expect(lastHand).toBe(String(final.handNumber))
  await page.getByLabel('跳转手牌').selectOption(lastHand!)
  await expect(page.locator('.replay-summary')).toContainText(`第 ${lastHand} 手`)
  const timeline = page.getByRole('slider', { name: '回放时间轴' })
  await timeline.press('End')
  await expect(page.locator('.replay-summary')).toContainText('锦标赛结束')
  await expect(page.locator('.replay-summary')).toContainText(`事件 ${final.sequence}`)
  await expect(page.locator('.replay-event')).toContainText('本手结算')
  await expect(page.locator('.replay-seats')).toContainText('冠军')
  await expect(page.getByRole('button', { name: '下一步', exact: true })).toBeDisabled()
  await page.screenshot({ path: test.info().outputPath('tournament-final-replay.png'), fullPage: true })
})
})

test('a dropped connection catches up, and an expired cookie returns to the lobby', async ({ page, context }) => {
  await create(page, true)
  const original = await (await page.request.get('/api/tables/current')).json()
  await context.setOffline(true)
  await expect(page.getByRole('alert')).toContainText('连接中断')
  await context.setOffline(false)
  await expect(page.getByRole('button', { name: '推进一步', exact: true })).toBeEnabled()
  expect((await (await page.request.get('/api/tables/current')).json()).tableId).toBe(original.tableId)
  await context.setOffline(true)
  await expect(page.getByRole('alert')).toContainText('连接中断')
  await context.clearCookies()
  await context.setOffline(false)
  await expect(page.getByRole('heading', { name: '今夜，和谁过招？' })).toBeVisible()
})

test('mobile controls fit, and keyboard typing never folds the player', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await create(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: '动态', exact: true }).click()
  const version = (await (await page.request.get('/api/tables/current')).json()).version
  await page.getByRole('textbox', { name: '牌桌发言' }).pressSequentially('fcr')
  expect((await (await page.request.get('/api/tables/current')).json()).version).toBe(version)
  await page.getByRole('button', { name: '关闭牌桌动态' }).click()
  const dock = await page.locator('.action-dock').boundingBox()
  expect(dock!.y + dock!.height).toBeLessThanOrEqual(844)
  const suits = page.locator('.hero-cards .card-suit')
  await expect(suits).toHaveCount(2)
  for (let index = 0; index < await suits.count(); index++) {
    expect(await suits.nth(index).evaluate(element => {
      const box = element.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      return hit === element || element.contains(hit) || hit === element.closest('.playing-card')
    })).toBe(true)
  }
  const box = await page.getByRole('button', { name: '全下', exact: true }).boundingBox()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: test.info().outputPath('mobile-table.png'), fullPage: true })
})

test('sound settings unlock, mute and preserve reduced motion on mobile', async ({ page }) => {
  await page.setViewportSize({width:390,height:844})
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await page.getByText('声效',{exact:true}).click()
  await page.getByRole('button',{name:'开启音效',exact:true}).click()
  await expect(page.getByRole('button',{name:'静音',exact:true})).toHaveAttribute('aria-pressed','true')
  await page.getByRole('button',{name:'静音',exact:true}).click()
  await page.getByRole('checkbox',{name:'减少动态效果'}).check()
  await page.getByRole('slider',{name:'音效音量'}).fill('25')
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true)
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([])
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByText('声效',{exact:true}).click()
  await expect(page.getByRole('button',{name:'开启音效',exact:true})).toBeVisible()
  await expect(page.getByRole('checkbox',{name:'减少动态效果'})).toBeChecked()
  await expect(page.getByRole('slider',{name:'音效音量'})).toHaveValue('25')
})

test('anonymous local career survives reload and requires confirmation to clear', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await page.evaluate(()=>localStorage.setItem('agent-tavern.career.v1',JSON.stringify({games:2,wins:1,hands:8,netChips:3000,biggestPot:4200,actions:{FOLD:2,CHECK:1,CALL:4,RAISE:2,ALL_IN:0},opponents:{},cursors:{},completedTables:[],tablePots:{}})))
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByRole('button',{name:'本地战绩'}).click()
  await expect(page.getByRole('heading',{name:'我的茶馆战绩'})).toBeVisible()
  await expect(page.getByText('50%')).toBeVisible();await expect(page.getByText('+3,000')).toBeVisible()
  await page.getByRole('button',{name:'清除本地战绩'}).click()
  await expect(page.getByText('此操作无法撤销。')).toBeVisible()
  await page.getByRole('button',{name:'确认清除'}).click()
  await expect(page.getByText('0%',{exact:true})).toBeVisible()
  await page.reload({ waitUntil: 'domcontentloaded' });await page.getByRole('button',{name:'本地战绩'}).click()
  await expect(page.getByText('0%',{exact:true})).toBeVisible()
})

test('lobby and table meet automated WCAG A/AA checks', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('button', { name: '落座，开局 →' })).toBeEnabled()
  expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations).toEqual([])
  await page.getByRole('button', { name: '落座，开局 →' }).click()
  await waitForPlayerTurn(page)
  expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations).toEqual([])
  await page.screenshot({ path: test.info().outputPath('desktop-table.png') })
})

test('an eliminated player keeps a live spectator session after refresh', async ({ page, context }) => {
  test.setTimeout(5 * 60_000)
  const startedAt = Date.now()
  await create(page)

  type FlowView = {
    tableId: string; version: number; sequence: number; handNumber: number; status: string; actorSeat: number | null
    selfSeat: number; canAdvance: boolean; holeCards: unknown[]
    legalActions: { types: string[] }
    seats: Array<{ seat: number; self: boolean; status: string; stack: number }>
    rankings: Array<{ seat: number; position: number | null }>
  }
  async function current(): Promise<FlowView> {
    const response = await page.request.get('/api/tables/current')
    if (!response.ok()) throw new Error(`Current table failed with HTTP ${response.status()}: ${await response.text()}`)
    return response.json()
  }
  async function submit(view: FlowView, endpoint: 'actions' | 'next-hand', type?: 'FOLD' | 'CHECK') {
    const response = await page.request.post(`/api/tables/current/${endpoint}`, { data: {
      ...(type ? { type } : {}), commandId: crypto.randomUUID(), tableId: view.tableId, expectedVersion: view.version,
    } })
    if (response.status() === 409) return current()
    if (!response.ok()) throw new Error(`${endpoint} failed with HTTP ${response.status()}: ${await response.text()}`)
    return response.json() as Promise<FlowView>
  }

  let view = await current()
  let commands = 0
  const maxCommands = 2_000
  const deadline = Date.now() + 4 * 60_000
  while (Date.now() < deadline && commands < maxCommands) {
    const self = view.seats.find(seat => seat.self)
    if (view.status === 'COMPLETE' || self?.status === 'ELIMINATED' || view.canAdvance) break

    if (view.status === 'BETWEEN_HANDS') {
      view = await submit(view, 'next-hand'); commands++
      continue
    }

    const type = view.legalActions.types.includes('FOLD') ? 'FOLD'
      : view.legalActions.types.includes('CHECK') ? 'CHECK' : null
    if (type) {
      view = await submit(view, 'actions', type); commands++
      continue
    }
    if (view.actorSeat === view.selfSeat)
      throw new Error(`Human turn offered neither FOLD nor CHECK: ${view.legalActions.types.join(',')}`)
    await page.waitForTimeout(40)
    view = await current()
  }

  const finalSelf = view.seats.find(seat => seat.self)
  if (view.status !== 'COMPLETE' && finalSelf?.status !== 'ELIMINATED') {
    throw new Error(`Player elimination did not finish within ${commands >= maxCommands ? 'the command limit' : 'the four-minute deadline'}: `
      + `${commands}/${maxCommands} commands, ${Date.now() - startedAt}ms, hand ${view.handNumber}, `
      + `table ${view.status}, player ${finalSelf?.status} with ${finalSelf?.stack} chips, `
      + `version ${view.version}, sequence ${view.sequence}, actor ${view.actorSeat}`)
  }

  if (view.status === 'COMPLETE') {
    const selfRank = view.rankings.find(rank => rank.seat === view.selfSeat)
    expect(selfRank?.position).toBeGreaterThanOrEqual(1)
    expect(selfRank?.position).toBeLessThanOrEqual(6)
    expect(view.holeCards).toEqual([])
    await expect(page.getByRole('heading', { name: '今夜的赢家', exact: true })).toBeVisible()
    await expect(page.locator('.final-ranks li')).toHaveCount(6)
    await expect(page.locator('.final-ranks li').first()).toContainText('#1')
    await expect(page.getByRole('textbox', { name: '牌桌发言' })).toHaveCount(0)
    console.log(`Natural player completion at rank ${selfRank?.position} after ${commands} commands and ${Date.now() - startedAt}ms`)
    return
  }

  const self = view.seats.find(seat => seat.self)
  expect(self?.status).toBe('ELIMINATED')
  expect(view.canAdvance).toBe(true)
  expect(view.holeCards).toEqual([])
  await expect(page.getByRole('textbox', { name: '牌桌发言' })).toHaveCount(0)
  await expect(page.locator('.hero-cards')).toHaveCount(0)

  const advance = page.getByRole('button', { name: /^(推进一步|下一手)$/ })
  await expect(advance).toBeEnabled()
  await expect(page.locator('.statusbar')).toContainText(`已确认事件 ${view.sequence}`)
  const observer = await context.newPage()
  await observer.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(observer.getByRole('button', { name: /^(推进一步|下一手)$/ })).toBeEnabled()
  await expect(observer.getByRole('textbox', { name: '牌桌发言' })).toHaveCount(0)
  await expect(observer.locator('.hero-cards')).toHaveCount(0)

  const beforeVersion = view.version
  await advance.click()
  await expect.poll(async () => (await current()).version).toBeGreaterThan(beforeVersion)
  view = await current()
  await expect(observer.locator('.statusbar')).toContainText(`已确认事件 ${view.sequence}`)

  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('button', { name: /^(推进一步|下一手)$/ })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: '牌桌发言' })).toHaveCount(0)
  await expect(page.locator('.hero-cards')).toHaveCount(0)
  const restored = await current()
  expect(restored.tableId).toBe(view.tableId)
  expect(restored.seats.find(seat => seat.self)?.status).toBe('ELIMINATED')
  expect(restored.holeCards).toEqual([])
  console.log(`Player elimination restored after ${commands} commands and ${Date.now() - startedAt}ms`)
})
