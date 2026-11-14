<template>
  <!-- eslint-disable @intlify/vue-i18n/no-raw-text -->
  <div
    ref="root"
    class="tvVirtualKeyboard"
    role="group"
    aria-label="Virtual keyboard"
  >
    <div class="tvKeyboardGrid">
      <button
        v-for="(key, idx) in keys"
        :key="idx"
        class="tvKey"
        :class="{
          'tvKey-special': key.special,
          'tvKey-space': key.value === ' ',
          'tvKey-enter': key.value === 'Enter',
          'tvKey-backspace': key.value === 'Backspace'
        }"
        :style="{ gridColumn: `span ${key.span || 1}` }"
        :aria-label="key.label || key.value"
        @click="handleKey(key)"
      >
        <span
          v-if="key.icon"
          class="tvKeyIcon"
        >
          <FontAwesomeIcon :icon="key.icon" />
        </span>
        <span
          v-else
          class="tvKeyLabel"
        >{{ key.display || key.value }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { useTemplateRef } from 'vue'

const root = useTemplateRef('root')

defineExpose({
  /** puts focus on the first key */
  focus: () => {
    root.value?.querySelector('button')?.focus()
  }
})

const emit = defineEmits(['input', 'backspace', 'space', 'enter', 'clear'])

defineProps({
  showNumbers: {
    type: Boolean,
    default: true
  }
})

// 10-column grid per spec. Special keys span multiple columns.
const keys = [
  // Row 1: numbers
  { value: '1', display: '1', span: 1 },
  { value: '2', display: '2', span: 1 },
  { value: '3', display: '3', span: 1 },
  { value: '4', display: '4', span: 1 },
  { value: '5', display: '5', span: 1 },
  { value: '6', display: '6', span: 1 },
  { value: '7', display: '7', span: 1 },
  { value: '8', display: '8', span: 1 },
  { value: '9', display: '9', span: 1 },
  { value: '0', display: '0', span: 1 },
  // Row 2: QWERTYUIOP
  { value: 'q', display: 'Q', span: 1 },
  { value: 'w', display: 'W', span: 1 },
  { value: 'e', display: 'E', span: 1 },
  { value: 'r', display: 'R', span: 1 },
  { value: 't', display: 'T', span: 1 },
  { value: 'y', display: 'Y', span: 1 },
  { value: 'u', display: 'U', span: 1 },
  { value: 'i', display: 'I', span: 1 },
  { value: 'o', display: 'O', span: 1 },
  { value: 'p', display: 'P', span: 1 },
  // Row 3: ASDFGHJKL (9) + Backspace span 1? But spec: special should span multiple, so Backspace spans 2 and row will wrap — we make it span 1 here and add extra row
  { value: 'a', display: 'A', span: 1 },
  { value: 's', display: 'S', span: 1 },
  { value: 'd', display: 'D', span: 1 },
  { value: 'f', display: 'F', span: 1 },
  { value: 'g', display: 'G', span: 1 },
  { value: 'h', display: 'H', span: 1 },
  { value: 'j', display: 'J', span: 1 },
  { value: 'k', display: 'K', span: 1 },
  { value: 'l', display: 'L', span: 1 },
  { value: 'Backspace', display: '', icon: ['fas', 'delete-left'], special: true, span: 1 },
  // Row 4: ZXCVBNM + , . -
  { value: 'z', display: 'Z', span: 1 },
  { value: 'x', display: 'X', span: 1 },
  { value: 'c', display: 'C', span: 1 },
  { value: 'v', display: 'V', span: 1 },
  { value: 'b', display: 'B', span: 1 },
  { value: 'n', display: 'N', span: 1 },
  { value: 'm', display: 'M', span: 1 },
  { value: ',', display: ',', span: 1 },
  { value: '.', display: '.', span: 1 },
  { value: '-', display: '-', span: 1 },
  // Bottom row: special keys spanning multiple columns
  { value: 'Clear', display: 'Clear', special: true, span: 2 },
  { value: ' ', display: 'Space', special: true, span: 4 },
  { value: 'Backspace', display: '', icon: ['fas', 'delete-left'], special: true, span: 2 },
  { value: 'Enter', display: 'Search', special: true, span: 2 }
]

function handleKey(key) {
  if (key.value === 'Backspace') {
    emit('backspace')
  } else if (key.value === ' ') {
    emit('space')
  } else if (key.value === 'Enter') {
    emit('enter')
  } else if (key.value === 'Clear') {
    emit('clear')
  } else {
    emit('input', key.value)
  }
}
</script>

<style scoped>
.tvVirtualKeyboard {
  margin-block-start: 20px;
  inline-size: 100%;
}

.tvKeyboardGrid {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: 8px;
  max-inline-size: 720px;
  margin-inline: auto;
}

.tvKey {
  background-color: var(--surface-dark);
  color: var(--text-main);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 6px;
  padding: 12px 4px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  min-block-size: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  transition: transform 0.1s ease-in-out, background-color 0.1s ease-in-out, border-color 0.1s;
}

.tvKey:hover {
  background-color: rgba(255,255,255,0.12);
  border-color: rgba(255,255,255,0.25);
}

.tvKey:active {
  background-color: rgba(255,255,255,0.2);
}

.tvKey:focus {
  outline: var(--focus-ring);
  outline-offset: 2px;
  transform: scale(1.05);
  background-color: rgba(255,255,255,0.1);
  z-index: 1;
}

.tvKey-special {
  background-color: var(--accent-red);
  color: var(--text-main);
  font-weight: 600;
  border-color: transparent;
}

.tvKey-special:hover {
  background-color: #cc0000;
}

.tvKey-special:focus {
  background-color: #ff2222;
}

.tvKey-space {
  /* Space spans 4 columns per spec */
}

.tvKey-enter {
  background-color: var(--accent-red);
}

.tvKey-backspace {
  background-color: #333;
}

.tvKey-backspace:hover {
  background-color: #444;
}

.tvKeyIcon {
  font-size: 18px;
}

.tvKeyLabel {
  line-height: 1;
}
</style>
