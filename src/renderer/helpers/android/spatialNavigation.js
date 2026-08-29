/**
 * Spatial navigation for television remotes.
 *
 * A d-pad reaches the web view as ordinary arrow keydown events, and a web page's
 * default action for those is to scroll. That leaves a remote user sliding the page
 * around past controls they can never reach. This moves focus to whichever focusable
 * element actually lies in the direction pressed, the way every other television app
 * behaves.
 *
 * The video player is one destination rather than a row of shaka buttons: arriving on it
 * focuses the container, the horizontal keys then seek as they do on the desktop build,
 * and the vertical keys are how you leave it again.
 *
 * The geometry is deliberately kept clear of the DOM so that it can be tested on its
 * own, without a browser.
 */

/**
 * @typedef {object} Rect
 * @property {number} left
 * @property {number} top
 * @property {number} right
 * @property {number} bottom
 */

/**
 * @typedef {'up' | 'down' | 'left' | 'right'} Direction
 */

/** @type {Record<string, Direction>} */
export const DIRECTION_BY_KEY = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right'
}

/**
 * How heavily a candidate is penalised for sitting off to the side of the direction of
 * travel. High enough that a badly aligned near neighbour loses to a well aligned
 * further one, which is what makes moving along a row or column feel predictable.
 */
const PERPENDICULAR_PENALTY = 3

/** Separates candidates that are otherwise tied, favouring matching centres. */
const CENTRE_PENALTY = 0.2

/**
 * Slack, in pixels, on the test for whether a candidate is in the direction of travel.
 * Without it a row whose items differ by a pixel in height stops being navigable.
 */
const EDGE_TOLERANCE = 2

/** Fraction of the viewport to scroll by when there is nothing left to focus. */
const SCROLL_STEP_RATIO = 0.6

/**
 * Restates a rect in terms of the direction of travel so that all four directions can
 * share one comparison. `leading` is the edge being navigated away from, `trailing` is
 * the edge a candidate is approached by, and the cross pair is the perpendicular axis.
 * Up and left are negated so that "further along" is always the larger number.
 * @param {Rect} rect
 * @param {Direction} direction
 */
function project(rect, direction) {
  switch (direction) {
    case 'down':
      return { leading: rect.bottom, trailing: rect.top, crossStart: rect.left, crossEnd: rect.right }
    case 'up':
      return { leading: -rect.top, trailing: -rect.bottom, crossStart: rect.left, crossEnd: rect.right }
    case 'right':
      return { leading: rect.right, trailing: rect.left, crossStart: rect.top, crossEnd: rect.bottom }
    case 'left':
      return { leading: -rect.left, trailing: -rect.right, crossStart: rect.top, crossEnd: rect.bottom }
    default:
      return null
  }
}

/**
 * Rates how good a move from one rect to another is, where lower is better.
 * @param {Rect} from
 * @param {Rect} to
 * @param {Direction} direction
 * @returns {number | null} the score, or null when `to` is not in that direction at all
 */
export function scoreCandidate(from, to, direction) {
  const source = project(from, direction)
  const candidate = project(to, direction)

  if (source === null || candidate === null) {
    return null
  }

  const distance = candidate.trailing - source.leading

  // behind us, so not a candidate for this direction
  if (distance < -EDGE_TOLERANCE) {
    return null
  }

  // zero whenever the two overlap on the perpendicular axis, which is what keeps a
  // straight move along a row cheap
  const gap = Math.max(
    0,
    source.crossStart - candidate.crossEnd,
    candidate.crossStart - source.crossEnd
  )

  const centreDelta = Math.abs(
    (source.crossStart + source.crossEnd) / 2 - (candidate.crossStart + candidate.crossEnd) / 2
  )

  return Math.max(0, distance) + gap * PERPENDICULAR_PENALTY + centreDelta * CENTRE_PENALTY
}

/**
 * Whether two rects share any span on the axis perpendicular to the direction of travel,
 * which is the difference between "directly below" and "below and off to one side".
 * @param {Rect} from
 * @param {Rect} to
 * @param {Direction} direction
 * @returns {boolean}
 */
export function overlapsPerpendicular(from, to, direction) {
  const source = project(from, direction)
  const candidate = project(to, direction)

  if (source === null || candidate === null) {
    return false
  }

  return source.crossStart < candidate.crossEnd && candidate.crossStart < source.crossEnd
}

/**
 * Picks where to move next.
 *
 * Candidates that line up with the direction of travel always beat ones that sit off to
 * the side, however close those are, and only when nothing lines up at all is a diagonal
 * move considered. Scoring alone is not enough for this: a neighbour a few pixels away
 * but well off to one side would otherwise win over the item directly below, and focus
 * would wander out of its column. The cost is that reaching something diagonal can take
 * two presses instead of one, which is the trade every television interface makes.
 *
 * @param {Rect} from
 * @param {Rect[]} candidates
 * @param {Direction} direction
 * @returns {number} index of the best candidate, or -1 when none lie in that direction
 */
export function pickBestCandidate(from, candidates, direction) {
  let alignedIndex = -1
  let alignedScore = Infinity
  let anyIndex = -1
  let anyScore = Infinity

  for (let index = 0; index < candidates.length; index++) {
    const score = scoreCandidate(from, candidates[index], direction)

    if (score === null) {
      continue
    }

    if (score < anyScore) {
      anyScore = score
      anyIndex = index
    }

    if (score < alignedScore && overlapsPerpendicular(from, candidates[index], direction)) {
      alignedScore = score
      alignedIndex = index
    }
  }

  return alignedIndex !== -1 ? alignedIndex : anyIndex
}

// #region DOM

const FOCUSABLE_SELECTOR = 'a[href], button, input, select, textarea, summary, [tabindex]'

/** The player is one place to arrive at, not a row of shaka buttons to step through. */
const PLAYER_SELECTOR = '.ftVideoPlayer'

/** FtPrompt already walks its own button row horizontally, and wraps around while doing it. */
const PROMPT_SELECTOR = '.prompt'

const HIDDEN_SELECTOR = '[inert], [aria-hidden="true"]'

/** Input types where the arrow keys change the value rather than doing nothing. */
const EDITABLE_INPUT_TYPES = new Set([
  'text', 'search', 'url', 'tel', 'email', 'password', 'number', 'range',
  'date', 'month', 'week', 'time', 'datetime-local'
])

/**
 * Whether the focused element already does something meaningful with the arrow key
 * pressed, in which case taking it away would be a regression.
 *
 * Exported so the policy can be tested without a browser; it only needs `tagName`,
 * `type`, `isContentEditable` and `closest` from what it is given.
 *
 * @param {Element} element
 * @param {string} key
 * @returns {boolean}
 */
export function ownsArrowKeys(element, key) {
  if (element.isContentEditable) {
    return true
  }

  const tagName = element.tagName

  if (tagName === 'TEXTAREA' || tagName === 'SELECT') {
    return true
  }

  if (tagName === 'INPUT' && EDITABLE_INPUT_TYPES.has((element.type || 'text').toLowerCase())) {
    return true
  }

  // While the player has focus the horizontal keys seek, exactly as they do on the
  // desktop build. The vertical keys are how you leave it again, which costs nothing
  // here: a remote's volume buttons are handled by the television itself and never
  // reach the app, so the player's volume binding is of no use on one.
  if (element.closest(PLAYER_SELECTOR) !== null) {
    return key === 'ArrowLeft' || key === 'ArrowRight'
  }

  // only the vertical keys are left for spatial navigation inside a prompt
  if ((key === 'ArrowLeft' || key === 'ArrowRight') && element.closest(PROMPT_SELECTOR) !== null) {
    return true
  }

  return false
}

/**
 * Whether an element is on screen at all. A zero sized rect covers `display: none`, and
 * checkVisibility, where this web view has it, covers the rest.
 * @param {Element} element
 * @returns {boolean}
 */
function isOnScreen(element) {
  // not in every web view version yet, so only trusted when it is actually there
  if (typeof element.checkVisibility === 'function' && !element.checkVisibility({ checkVisibilityCSS: true })) {
    return false
  }

  const rect = element.getBoundingClientRect()

  return rect.width > 0 && rect.height > 0
}

/**
 * @param {Element} element
 * @returns {boolean}
 */
function isReachable(element) {
  return element.closest(HIDDEN_SELECTOR) === null && isOnScreen(element)
}

/**
 * @param {Element} element
 * @returns {boolean}
 */
function isNavigable(element) {
  if (element.tabIndex < 0 || element.disabled) {
    return false
  }

  // the player joins the candidates as a whole, below, so the controls inside it are not
  // destinations in their own right
  if (element.closest(PLAYER_SELECTOR) !== null) {
    return false
  }

  return isReachable(element)
}

/**
 * Where candidates may be looked for. While something is fullscreen nothing outside it is
 * on screen, and focus landing out there would strand the viewer on a control they cannot
 * see.
 * @returns {Document | Element}
 */
function navigationRoot() {
  return document.fullscreenElement ?? document
}

/**
 * @returns {Element[]}
 */
function getNavigableElements() {
  const root = navigationRoot()
  const elements = Array.prototype.filter.call(root.querySelectorAll(FOCUSABLE_SELECTOR), isNavigable)

  for (const player of root.querySelectorAll(PLAYER_SELECTOR)) {
    if (isReachable(player)) {
      elements.push(player)
    }
  }

  return elements
}

/**
 * Where the very first press should land, when nothing worth moving from is focused.
 * Prefers what is already on screen, topmost first.
 * @param {Element[]} elements
 * @returns {Element}
 */
function firstOnScreen(elements) {
  let best = elements[0]
  let bestScore = Infinity

  for (const element of elements) {
    const rect = element.getBoundingClientRect()
    const offScreen = rect.bottom < 0 || rect.top > window.innerHeight
    const score = (offScreen ? 1e6 : 0) + Math.max(0, rect.top) + rect.left / 1000

    if (score < bestScore) {
      bestScore = score
      best = element
    }
  }

  return best
}

/**
 * @param {Element} element
 */
function focusElement(element) {
  element.focus({ preventScroll: true })
  element.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}

/**
 * @param {Direction} direction
 * @returns {boolean} whether focus actually moved
 */
export function moveFocus(direction) {
  const elements = getNavigableElements()

  if (elements.length === 0) {
    return false
  }

  const active = document.activeElement
  const isOnACandidate = active !== null && elements.includes(active)

  if (!isOnACandidate) {
    // the first press should land somewhere rather than doing nothing
    focusElement(firstOnScreen(elements))
    return true
  }

  const others = elements.filter(element => element !== active)
  const rects = others.map(element => element.getBoundingClientRect())
  const index = pickBestCandidate(active.getBoundingClientRect(), rects, direction)

  if (index === -1) {
    return false
  }

  focusElement(others[index])

  return true
}

/**
 * Nudges the page along when there is nothing left to focus, so that content without any
 * controls in it, a long description say, can still be read from the sofa.
 * @param {Direction} direction
 */
function scrollInDirection(direction) {
  const vertical = direction === 'up' || direction === 'down'
  const step = Math.round((vertical ? window.innerHeight : window.innerWidth) * SCROLL_STEP_RATIO)
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  window.scrollBy({
    top: vertical ? (direction === 'down' ? step : -step) : 0,
    left: vertical ? 0 : (direction === 'right' ? step : -step),
    behavior: reduceMotion ? 'auto' : 'smooth'
  })
}

/**
 * @param {KeyboardEvent} event
 */
function handleKeyDown(event) {
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
    return
  }

  const direction = DIRECTION_BY_KEY[event.key]

  if (direction === undefined) {
    return
  }

  const active = document.activeElement

  if (active !== null && ownsArrowKeys(active, event.key)) {
    return
  }

  if (!moveFocus(direction)) {
    scrollInDirection(direction)
  }

  // This key meant "move focus", and nothing else. Stopping propagation from the capture
  // phase is what keeps the video player's own document level handler from seeing it and
  // seeking at the same time.
  event.preventDefault()
  event.stopPropagation()
}

/**
 * Starts translating arrow keys into focus movement.
 * @returns {() => void} call to stop again
 */
export function installSpatialNavigation() {
  document.addEventListener('keydown', handleKeyDown, true)

  return function uninstallSpatialNavigation() {
    document.removeEventListener('keydown', handleKeyDown, true)
  }
}

// #endregion
