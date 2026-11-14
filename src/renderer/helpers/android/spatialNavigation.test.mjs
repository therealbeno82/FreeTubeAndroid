/**
 * Tests for the geometry behind d-pad navigation.
 *
 *   node src/renderer/helpers/android/spatialNavigation.test.mjs
 *
 * No test runner and no dependencies, because the repository has neither. The layouts
 * below are modelled on a subscriptions page: a top bar, a side nav, and a grid of video
 * cards. Only the pure scoring functions are covered; anything touching the DOM needs a
 * device.
 */

import {
  overlapsPerpendicular,
  ownsArrowKeys,
  pickBestCandidate,
  scoreCandidate
} from './spatialNavigation.js'
import { versionNumberGt } from './utils.js'
import { awaitAsyncResultFrom } from './asyncResult.js'

const rect = (left, top, right, bottom) => ({ left, top, right, bottom })

let passed = 0
let failed = 0

function check (label, got, want) {
  if (got === want) {
    passed++
    console.log(`  ok    ${label}`)
  } else {
    failed++
    console.log(`  FAIL  ${label}\n          got ${got}, want ${want}`)
  }
}

const topSearch = rect(220, 10, 700, 50)
const topProfile = rect(720, 10, 780, 50)
const nav0 = rect(0, 100, 200, 140)
const nav1 = rect(0, 160, 200, 200)
const nav2 = rect(0, 220, 200, 260)
const card00 = rect(220, 100, 500, 300)
const card01 = rect(520, 100, 800, 300)
const card02 = rect(820, 100, 1100, 300)
const card10 = rect(220, 320, 500, 520)
const card11 = rect(520, 320, 800, 520)
const card12 = rect(820, 320, 1100, 520)

const page = { topSearch, topProfile, nav0, nav1, nav2, card00, card01, card02, card10, card11, card12 }
const names = new Map(Object.entries(page).map(([name, value]) => [value, name]))
const everything = Object.values(page)

/** Moves from one named rect and reports where focus lands. */
function move (source, direction) {
  const others = everything.filter(candidate => candidate !== source)
  const index = pickBestCandidate(source, others, direction)

  return index === -1 ? null : names.get(others[index])
}

console.log('\na column moves one step at a time')
check('nav0 down stops at nav1, not nav2', move(nav0, 'down'), 'nav1')
check('nav1 up returns to nav0', move(nav1, 'up'), 'nav0')
check('nav0 right crosses into the grid', move(nav0, 'right'), 'card00')

console.log('\na grid moves along its rows and columns')
check('card00 right', move(card00, 'right'), 'card01')
check('card01 right', move(card01, 'right'), 'card02')
check('card02 right runs out of row', move(card02, 'right'), null)
check('card00 down', move(card00, 'down'), 'card10')
check('card01 down stays in its column', move(card01, 'down'), 'card11')
check('card12 up stays in its column', move(card12, 'up'), 'card02')
check('card00 up reaches the top bar', move(card00, 'up'), 'topSearch')
check('card10 left picks the nearest nav row', move(card10, 'left'), 'nav2')

console.log('\nnothing behind the direction of travel is a candidate')
check('card00 is not below card10', scoreCandidate(card10, card00, 'down'), null)
check('card01 is not left of card00', scoreCandidate(card00, card01, 'left'), null)
check('an element is never its own candidate', scoreCandidate(nav0, nav0, 'down'), null)

console.log('\nrows whose items differ by a pixel stay navigable')
const item = rect(0, 100, 100, 140)
check('a 2px overlap is still a move', scoreCandidate(item, rect(98, 100, 200, 140), 'right') !== null, true)
check('a 20px overlap is not', scoreCandidate(item, rect(80, 100, 200, 140), 'right'), null)

console.log('\nalignment outranks proximity')
const box = rect(0, 0, 100, 50)
const diagonalAndVeryClose = rect(105, 55, 205, 105)
const alignedButFarBelow = rect(0, 400, 100, 450)
check(
  'raw score alone would take the diagonal',
  scoreCandidate(box, diagonalAndVeryClose, 'down') < scoreCandidate(box, alignedButFarBelow, 'down'),
  true
)
check('the overlap test tells them apart', overlapsPerpendicular(box, diagonalAndVeryClose, 'down'), false)
check('so the aligned one wins', pickBestCandidate(box, [diagonalAndVeryClose, alignedButFarBelow], 'down'), 1)
check('and a diagonal is still reachable alone', pickBestCandidate(box, [diagonalAndVeryClose], 'down'), 0)

console.log('\nwho owns the arrow keys')

/** Enough of an element for the ownership rule, which never touches the real DOM. */
function element ({ tagName = 'DIV', type, isContentEditable = false, within = [] } = {}) {
  return {
    tagName,
    type,
    isContentEditable,
    closest: selector => (within.some(ancestor => selector.includes(ancestor)) ? {} : null)
  }
}

const link = element({ tagName: 'A' })
const textBox = element({ tagName: 'INPUT', type: 'text' })
const checkbox = element({ tagName: 'INPUT', type: 'checkbox' })
const slider = element({ tagName: 'INPUT', type: 'range' })
const inThePlayer = element({ within: ['.ftVideoPlayer'] })
const inAPrompt = element({ tagName: 'BUTTON', within: ['.prompt'] })

check('a link keeps none of them', ownsArrowKeys(link, 'ArrowDown'), false)
check('a text box types with them', ownsArrowKeys(textBox, 'ArrowLeft'), true)
check('a textarea does too', ownsArrowKeys(element({ tagName: 'TEXTAREA' }), 'ArrowDown'), true)
check('a select changes option', ownsArrowKeys(element({ tagName: 'SELECT' }), 'ArrowDown'), true)
check('a slider changes value', ownsArrowKeys(slider, 'ArrowRight'), true)
check('a checkbox does nothing with them', ownsArrowKeys(checkbox, 'ArrowRight'), false)
check('contenteditable moves the caret', ownsArrowKeys(element({ isContentEditable: true }), 'ArrowUp'), true)

check('the player seeks on left', ownsArrowKeys(inThePlayer, 'ArrowLeft'), true)
check('the player seeks on right', ownsArrowKeys(inThePlayer, 'ArrowRight'), true)
check('but up leaves the player', ownsArrowKeys(inThePlayer, 'ArrowUp'), false)
check('and down leaves the player', ownsArrowKeys(inThePlayer, 'ArrowDown'), false)

check('a prompt walks its own button row', ownsArrowKeys(inAPrompt, 'ArrowRight'), true)
check('but vertical keys still navigate it', ownsArrowKeys(inAPrompt, 'ArrowDown'), false)

console.log('\nversion numbers compare by numeric segment')
check('1.10.0 is newer than 1.9.0', versionNumberGt('1.10.0', '1.9.0'), true)
check('v0.25.2 is not newer than 0.25.2', versionNumberGt('v0.25.2', '0.25.2'), false)
check('0.25.3 is newer than 0.25.2', versionNumberGt('0.25.3', '0.25.2'), true)
check('1.0 is not newer than 1.0.0', versionNumberGt('1.0', '1.0.0'), false)
check('1.0.1 is newer than 1.0', versionNumberGt('1.0.1', '1.0'), true)

console.log('\nasync bridge results keep their outcome')

/**
 * Stands in for the native side: holds a message and its status the way
 * AsyncJSCommunicator does, and dispatches the same events.
 */
function fakeBridge () {
  const messages = new Map()
  const statuses = new Map()
  const target = new EventTarget()

  const settle = (status) => (id, message) => {
    messages.set(id, message)
    statuses.set(id, status)
    target.dispatchEvent(new Event(`${id}-${status}`))
  }

  return {
    target,
    resolve: settle('resolve'),
    reject: settle('reject'),
    bridge: {
      getSyncStatus: (id) => statuses.get(id) ?? null,
      getSyncMessage: (id) => {
        const message = messages.get(id) ?? null
        messages.delete(id)
        statuses.delete(id)
        return message
      }
    }
  }
}

/** @returns {Promise<string>} how the promise ended, and with what */
const outcome = (promise) => promise.then(
  (value) => `resolved:${value}`,
  (error) => `rejected:${error instanceof Error ? error.message : error}`
)

{
  const { bridge, target, reject } = fakeBridge()
  reject('a', 'File not found from given uri')
  check(
    'a rejection that lands before anyone listens is still a rejection',
    await outcome(awaitAsyncResultFrom(bridge, target, 'a')),
    'rejected:File not found from given uri'
  )
}

{
  const { bridge, target, resolve } = fakeBridge()
  resolve('b', 'contents')
  check(
    'a resolution that lands early resolves',
    await outcome(awaitAsyncResultFrom(bridge, target, 'b')),
    'resolved:contents'
  )
}

{
  const { bridge, target, resolve } = fakeBridge()
  resolve('c', '')
  check(
    'an early resolution with an empty result is not mistaken for pending',
    await outcome(awaitAsyncResultFrom(bridge, target, 'c', 50)),
    'resolved:'
  )
}

{
  const { bridge, target, reject } = fakeBridge()
  const pending = outcome(awaitAsyncResultFrom(bridge, target, 'd'))
  reject('d', 'nope')
  check('a rejection that lands later rejects', await pending, 'rejected:nope')
}

{
  const { bridge, target, resolve } = fakeBridge()
  const pending = outcome(awaitAsyncResultFrom(bridge, target, 'e'))
  resolve('e', 'late')
  check('a resolution that lands later resolves', await pending, 'resolved:late')
}

{
  const { bridge, target } = fakeBridge()
  check(
    'nothing arriving at all times out',
    await outcome(awaitAsyncResultFrom(bridge, target, 'f', 10)),
    'rejected:Async result timed out: f'
  )
}

console.log(`\n${passed} passed, ${failed} failed\n`)

process.exit(failed === 0 ? 0 : 1)
