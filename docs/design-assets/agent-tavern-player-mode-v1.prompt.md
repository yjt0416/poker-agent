# Agent Tavern Player Mode Visual Prompt

- Status: approved
- Date: 2026-08-25
- Generator: Codex built-in ImageGen
- Use case: `ui-mockup`
- Output: `agent-tavern-player-mode-v1.png`
- Reference role: the user-provided image was used only for composition, mood, and quality direction.

## Generation prompt

```text
Use case: ui-mockup
Asset type: high-fidelity desktop web game interface visual draft, 16:9 landscape
Input images: Image 1 is a composition, mood, and quality reference only; generate a new original interface, do not copy its characters or ornamental details exactly.
Primary request: Create a shippable-looking player-mode UI mockup for a multi-agent Texas Hold'em game called "AGENT TAVERN". The interface must feel substantially more polished than a wireframe.
Scene/backdrop: an immersive warm fantasy tavern at night, amber lantern light, dark timber, subtle fireplace glow, deep atmospheric background.
Subject: a large oval green-felt poker table with five distinct AI opponents seated around it and the human player represented at the bottom foreground. AI opponents are original 2D hand-painted chibi fantasy animal characters with expressive faces and slightly rough/simple character modeling: a clever fox, confident boar, elegant cat, cautious rabbit, and gruff bulldog. Keep character rendering lighter-cost than the UI chrome while still cohesive.
Style/medium: realistic commercial game UI mockup with premium hand-painted 2D fantasy tavern art; sophisticated layered dark wood, aged brass, blackened metal, embossed leather, and green velvet interface materials; elegant fantasy card-game polish; practical web UI, not concept art.
Composition/framing: 1920x1080-style wide desktop screen. Central stage occupies about 65% width. Slim tournament/status panels on the left. Slim combined TABLE TALK and ACTION LOG panel on the right. Bottom center shows the human player's two hole cards and identity plate. Bottom right has large action controls. Preserve generous visibility of all five AI characters and the cards.
Key UI content: top centered title plaque with exact text "AGENT TAVERN"; left panel headings "TOURNAMENT" and "BLINDS"; right panel headings "TABLE TALK" and "ACTION LOG"; central pot badge "POT 2,450"; player cards A of spades and K of hearts; community cards 10 of hearts, J of clubs, Q of diamonds, plus two face-down card slots; bottom action buttons with exact labels "FOLD", "CALL", "RAISE"; a small chat input/button labeled "SAY SOMETHING"; one tasteful speech bubble from the active fox opponent; subtle active-turn gold rim light; clear chip stacks and dealer button.
Lighting/mood: intimate, lively, mischievous, cinematic amber light with restrained contrast; readable UI hierarchy and accessibility despite the dark scene.
Color palette: charcoal black, dark walnut, forest green, antique brass, warm amber, small red/green action accents.
Materials/textures: high-detail wood grain, beveled brass borders, leather panels, velvet felt, crisp playing cards and chips; consistent icon system.
Constraints: original design; no copied logos or trademarked characters; no watermark; no photorealistic humans; no 3D low-poly look; no neon sci-fi; no oversized decorative frame that hides gameplay; avoid clutter; no private AI chain-of-thought panel in player mode; make all major text legible and correctly spelled; UI chrome must look polished even if character art is intentionally simpler.
```

## Rule-alignment edit prompt

```text
Use case: precise-object-edit
Asset type: high-fidelity desktop web game UI visual draft
Input images: Image 1 is the edit target.
Primary request: Correct only the tournament information in the left-side panels so it matches a six-player sit-and-go tournament with no ante.
Exact edits:
1. In the TOURNAMENT panel, replace "Players 6 / 24" with exact text "Players 6 / 6".
2. Replace the "PRIZE POOL 72,000" subsection with exact label "TOTAL CHIPS" and exact value "60,000".
3. In the BLINDS panel, remove the "Ante 100" row and remove "Next Ante 150".
4. Keep "Small Blind 250", "Big Blind 500", and "Next Blind (01:23) 300 / 600".
Constraints: change only those left-panel text and row details; preserve the full 16:9 composition, all five chibi animal opponents, human player area, poker table, cards, chips, title "AGENT TAVERN", right panels, action buttons, lighting, colors, materials, proportions, typography style, and all other content exactly as closely as possible; no new elements; no watermark; keep all corrected text legible and correctly spelled.
```

