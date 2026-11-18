import { createRoot } from 'react-dom/client';
import { App } from './ui/App';
import './ui/styles.css';

const mode = (document.body.dataset.mode ?? 'app') as 'popup' | 'sidepanel' | 'app';
createRoot(document.getElementById('root')!).render(<App mode={mode} />);
