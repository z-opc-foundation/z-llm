import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-llm-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/providers" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-llm 大模型中心" appShort="LLM"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
