/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        emerald: {
          50: '#ecfdf5',
          100: '#d1fae5',
          200: '#a7f3d0',
          300: '#6ee7b7',
          400: '#34d399',
          500: '#10b981',
          600: '#059669',
          700: '#047857',
          800: '#065f46',
          900: '#064e3b',
          950: '#022c22',
        },
        // shadcn-style semantic tokens
        background: '#000000',
        foreground: '#fafafa',
        card: {
          DEFAULT: '#0a0a0a',
          foreground: '#fafafa',
        },
        popover: {
          DEFAULT: '#0a0a0a',
          foreground: '#fafafa',
        },
        primary: {
          DEFAULT: '#10b981',
          foreground: '#022c22',
        },
        secondary: {
          DEFAULT: '#131313',
          foreground: '#fafafa',
        },
        muted: {
          DEFAULT: '#131313',
          foreground: '#a3a3a3',
        },
        accent: {
          DEFAULT: '#131313',
          foreground: '#fafafa',
        },
        destructive: {
          DEFAULT: '#ef4444',
          foreground: '#fafafa',
        },
        success: {
          DEFAULT: '#10b981',
          foreground: '#022c22',
        },
        warning: {
          DEFAULT: '#f59e0b',
          foreground: '#022c22',
        },
        info: {
          DEFAULT: '#38bdf8',
          foreground: '#022c22',
        },
        border: '#1f1f1f',
        input: '#1f1f1f',
        ring: '#10b981',
      },
      borderRadius: {
        xl: '14px',
        lg: '12px',
        md: '10px',
        sm: '8px',
      },
    },
  },
  plugins: [],
};
